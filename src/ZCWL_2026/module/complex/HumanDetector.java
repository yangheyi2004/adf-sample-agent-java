package ZCWL_2026.module.complex;

import adf.core.component.module.algorithm.Clustering;
import adf.core.component.communication.CommunicationMessage;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.information.*;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.util.*;
import static rescuecore2.standard.entities.StandardEntityURN.*;

public class HumanDetector extends adf.core.component.module.complex.HumanDetector {

    private Clustering clustering;
    private EntityID result;
    private MessageManager msgManager;
    private Set<EntityID> reportedVictims;
    private Set<EntityID> knownBuriedVictims;      // 消防车：已知被掩埋的单位（包括平民和其他智能体）
    private Set<EntityID> knownUnburiedVictims;    // 救护车：已挖出待装载的伤员（仅平民）
    private StandardEntityURN agentType;
    
    private int lastLogTime;
    private static final int LOG_INTERVAL = 10;
    
    // ========== 救护车全量扫描计时 ==========
    private int lastFullScanTime = 0;
    private static final int FULL_SCAN_INTERVAL = 5; // 每5步全量扫描一次

    // ========== 救护车：已装载平民缓存 ==========
    private Set<EntityID> loadedVictimsCache;

    // ========== 消防车：目标超时放弃相关 ==========
    private Map<EntityID, Integer> targetStartTime;      // 目标开始分配的时间
    private Map<EntityID, Integer> targetLastProgress;   // 最后取得进展的时间
    private Map<EntityID, Integer> targetLastBuriedness; // 上次掩埋度（用于判断进展）
    private static final int TARGET_TIMEOUT = 60;         // 60步无进展放弃

    public HumanDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                         ModuleManager mm, DevelopData dd) {
        super(ai, wi, si, mm, dd);
        this.knownBuriedVictims = new HashSet<>();
        this.knownUnburiedVictims = new HashSet<>();
        this.reportedVictims = new HashSet<>();
        this.agentType = ai.me().getStandardURN();
        this.lastLogTime = 0;
        this.loadedVictimsCache = new HashSet<>();

        // 初始化超时相关 Map
        this.targetStartTime = new HashMap<>();
        this.targetLastProgress = new HashMap<>();
        this.targetLastBuriedness = new HashMap<>();

        switch (si.getMode()) {
            case PRECOMPUTATION_PHASE:
            case PRECOMPUTED:
            case NON_PRECOMPUTE:
                this.clustering = mm.getModule(
                    "HumanDetector.Clustering",
                    "ZCWL_2026.module.algorithm.SampleKMeans");
                break;
        }
        registerModule(this.clustering);
    }

    @Override
    public HumanDetector updateInfo(MessageManager mm) {
        super.updateInfo(mm);
        this.msgManager = mm;
        int currentTime = this.agentInfo.getTime();
        
        // ========== 消防车：主动扫描世界变化，添加新掩埋目标 ==========
        if (this.agentType == FIRE_BRIGADE) {
            for (EntityID id : worldInfo.getChanged().getChangedEntities()) {
                StandardEntity e = worldInfo.getEntity(id);
                if (e instanceof Civilian) {
                    Civilian c = (Civilian) e;
                    if (c.isHPDefined() && c.getHP() > 0 
                        && c.isBuriednessDefined() && c.getBuriedness() > 0
                        && c.isPositionDefined()) {
                        if (knownBuriedVictims.add(c.getID())) {
                            // 新目标，记录开始时间和初始进展
                            targetStartTime.put(c.getID(), currentTime);
                            targetLastProgress.put(c.getID(), currentTime);
                            targetLastBuriedness.put(c.getID(), c.getBuriedness());
                            System.err.println("[HumanDetector] 消防车 " + agentInfo.getID() + 
                                " 发现新掩埋平民: " + c.getID());
                        }
                    }
                }
            }
            
            // ========== 检查当前目标的进展（掩埋度是否减小） ==========
            if (this.result != null && knownBuriedVictims.contains(this.result)) {
                Human victim = (Human) this.worldInfo.getEntity(this.result);
                if (victim != null && victim.isBuriednessDefined()) {
                    int oldBuried = targetLastBuriedness.getOrDefault(this.result, -1);
                    int newBuried = victim.getBuriedness();
                    if (oldBuried > newBuried && newBuried >= 0) {
                        // 有进展，更新最后进展时间
                        targetLastProgress.put(this.result, currentTime);
                        targetLastBuriedness.put(this.result, newBuried);
                    } else if (oldBuried == -1) {
                        targetLastBuriedness.put(this.result, newBuried);
                        targetLastProgress.put(this.result, currentTime);
                    }
                }
            }
            
            // ========== 清理超过60步无进展的目标 ==========
            List<EntityID> timeoutVictims = new ArrayList<>();
            for (EntityID vid : knownBuriedVictims) {
                Integer lastProg = targetLastProgress.get(vid);
                if (lastProg == null) continue;
                if (currentTime - lastProg > TARGET_TIMEOUT) {
                    timeoutVictims.add(vid);
                    System.err.printf("[HumanDetector] 消防车 %d 放弃目标 %d: 超过 %d 步无进展%n",
                        this.agentInfo.getID().getValue(), vid.getValue(), TARGET_TIMEOUT);
                }
            }
            for (EntityID vid : timeoutVictims) {
                knownBuriedVictims.remove(vid);
                targetStartTime.remove(vid);
                targetLastProgress.remove(vid);
                targetLastBuriedness.remove(vid);
                // 如果当前结果正是被放弃的目标，清空结果
                if (this.result != null && this.result.equals(vid)) {
                    this.result = null;
                }
            }
        }
        
        // ========== 救护车：主动扫描被挖出的平民 ==========
        if (this.agentType == AMBULANCE_TEAM) {
            // 1. 世界变化扫描
            for (EntityID id : worldInfo.getChanged().getChangedEntities()) {
                StandardEntity e = worldInfo.getEntity(id);
                if (e instanceof Civilian) {
                    Civilian c = (Civilian) e;
                    // 已挖出且受伤的平民加入待装载列表
                    if (c.isHPDefined() && c.getHP() > 0 
                        && c.isBuriednessDefined() && c.getBuriedness() == 0
                        && c.isDamageDefined() && c.getDamage() > 0
                        && c.isPositionDefined()) {
                        if (loadedVictimsCache.contains(c.getID())) {
                            continue;
                        }
                        if (knownUnburiedVictims.add(c.getID())) {
                            System.err.println("[HumanDetector] 救护车 " + agentInfo.getID() + 
                                " 世界变化发现新伤员: " + c.getID());
                        }
                    }
                }
            }

            // 2. 定期全量扫描
            if (currentTime - lastFullScanTime >= FULL_SCAN_INTERVAL) {
                lastFullScanTime = currentTime;
                cleanupVictims(); // 先清理已失效的平民
                for (StandardEntity e : this.worldInfo.getEntitiesOfType(CIVILIAN)) {
                    Civilian c = (Civilian) e;
                    if (c.isHPDefined() && c.getHP() > 0 
                        && c.isBuriednessDefined() && c.getBuriedness() == 0
                        && c.isDamageDefined() && c.getDamage() > 0
                        && c.isPositionDefined()) {
                        if (loadedVictimsCache.contains(c.getID())) continue;
                        if (knownUnburiedVictims.add(c.getID())) {
                            System.err.println("[HumanDetector] 救护车 " + agentInfo.getID() + 
                                " 全量扫描发现伤员: " + c.getID());
                        }
                    }
                }
            }
        }
        
        // ========== 处理接收到的消息 ==========
        List<CommunicationMessage> messages = mm.getReceivedMessageList();
        for (CommunicationMessage msg : messages) {
            if (msg instanceof MessageCivilian) {
                MessageCivilian mc = (MessageCivilian) msg;
                EntityID victimId = mc.getAgentID();
                
                // 语音消息中已挖出且受伤的平民，加入待装载列表
                if (mc.isRadio() && mc.isBuriednessDefined() && mc.getBuriedness() == 0
                        && mc.isDamageDefined() && mc.getDamage() > 0) {
                    if (this.agentType == AMBULANCE_TEAM) {
                        StandardEntity e = worldInfo.getEntity(victimId);
                        if (e instanceof Civilian) {
                            Civilian c = (Civilian) e;
                            if (c.isPositionDefined() && c.getPosition() != null) {
                                if (!loadedVictimsCache.contains(victimId) && knownUnburiedVictims.add(victimId)) {
                                    System.err.println("[HumanDetector] 救护车 " + agentInfo.getID() + 
                                        " 收到救出语音消息，加入待装载列表: " + victimId);
                                }
                            } else {
                                // 位置无效，说明已被装载
                                if (knownUnburiedVictims.remove(victimId)) {
                                    System.err.println("[HumanDetector] 救护车 " + agentInfo.getID() + 
                                        " 平民 " + victimId + " 位置无效，从待装载列表移除");
                                }
                                loadedVictimsCache.add(victimId);
                            }
                        }
                    }
                    // 同时从消防车的掩埋列表中移除（如果存在）
                    knownBuriedVictims.remove(victimId);
                }
                
                // 消防车处理掩埋信息
                if (this.agentType == FIRE_BRIGADE) {
                    if (mc.isBuriednessDefined() && mc.getBuriedness() > 0) {
                        if (knownBuriedVictims.add(victimId)) {
                            targetStartTime.put(victimId, currentTime);
                            targetLastProgress.put(victimId, currentTime);
                            targetLastBuriedness.put(victimId, mc.getBuriedness());
                        }
                    }
                }
                processCivilianMessage(mc);
                
            } else if (msg instanceof MessageFireBrigade) {
                processFireBrigadeMessage((MessageFireBrigade) msg);
            } else if (msg instanceof MessagePoliceForce) {
                MessagePoliceForce mpf = (MessagePoliceForce) msg;
                if (this.agentType == FIRE_BRIGADE && mpf.isBuriednessDefined() && mpf.getBuriedness() > 0) {
                    if (knownBuriedVictims.add(mpf.getAgentID())) {
                        targetStartTime.put(mpf.getAgentID(), currentTime);
                        targetLastProgress.put(mpf.getAgentID(), currentTime);
                        targetLastBuriedness.put(mpf.getAgentID(), 0);
                    }
                    scanBuildingForVictims(mpf.getPosition());
                }
            } else if (msg instanceof MessageAmbulanceTeam) {
                MessageAmbulanceTeam mat = (MessageAmbulanceTeam) msg;
                if (this.agentType == FIRE_BRIGADE && mat.isBuriednessDefined() && mat.getBuriedness() > 0) {
                    if (knownBuriedVictims.add(mat.getAgentID())) {
                        targetStartTime.put(mat.getAgentID(), currentTime);
                        targetLastProgress.put(mat.getAgentID(), currentTime);
                        targetLastBuriedness.put(mat.getAgentID(), 0);
                    }
                    scanBuildingForVictims(mat.getPosition());
                }
                // 救护车处理其他救护车的装载/卸载消息
                if (this.agentType == AMBULANCE_TEAM) {
                    if (mat.getAction() == MessageAmbulanceTeam.ACTION_LOAD) {
                        EntityID victimId = mat.getTargetID();
                        if (victimId != null) {
                            if (knownUnburiedVictims.remove(victimId)) {
                                System.err.println("[HumanDetector] 救护车 " + agentInfo.getID() + 
                                    " 听到其他救护车装载了平民 " + victimId + "，移出待装载列表");
                            }
                            loadedVictimsCache.add(victimId);
                        }
                    } else if (mat.getAction() == MessageAmbulanceTeam.ACTION_UNLOAD) {
                        EntityID victimId = mat.getTargetID();
                        if (victimId != null && knownUnburiedVictims.remove(victimId)) {
                            System.err.println("[HumanDetector] 救护车 " + agentInfo.getID() + 
                                " 听到卸载平民 " + victimId + "，移出待装载列表");
                        }
                    }
                }
            }
        }
        
        cleanupVictims();
        
        int currentTimeLog = this.agentInfo.getTime();
        if (currentTimeLog - lastLogTime >= LOG_INTERVAL) {
            lastLogTime = currentTimeLog;
            // 可选的定期状态日志
        }
        
        return this;
    }
    
    private void processCivilianMessage(MessageCivilian mc) {
        EntityID victimId = mc.getAgentID();
        if (mc.isHPDefined() && mc.getHP() == 0) return;
        boolean isBuried = mc.isBuriednessDefined() && mc.getBuriedness() > 0;
        boolean hasDamage = mc.isDamageDefined() && mc.getDamage() > 0;
        
        if (this.agentType == AMBULANCE_TEAM) {
            if (!isBuried && hasDamage) {
                StandardEntity e = worldInfo.getEntity(victimId);
                if (e instanceof Civilian && !loadedVictimsCache.contains(victimId)) {
                    knownUnburiedVictims.add(victimId);
                }
            }
        } else if (this.agentType == FIRE_BRIGADE) {
            if (isBuried) {
                if (knownBuriedVictims.add(victimId)) {
                    int now = this.agentInfo.getTime();
                    targetStartTime.put(victimId, now);
                    targetLastProgress.put(victimId, now);
                    targetLastBuriedness.put(victimId, mc.getBuriedness());
                }
                if (mc.isPositionDefined()) {
                    scanBuildingForVictims(mc.getPosition());
                }
            }
        }
    }
    
    private void processFireBrigadeMessage(MessageFireBrigade mfb) {
        if (this.agentType != FIRE_BRIGADE) return;
        if (mfb.isBuriednessDefined() && mfb.getBuriedness() > 0) {
            if (knownBuriedVictims.add(mfb.getAgentID())) {
                int now = this.agentInfo.getTime();
                targetStartTime.put(mfb.getAgentID(), now);
                targetLastProgress.put(mfb.getAgentID(), now);
                targetLastBuriedness.put(mfb.getAgentID(), 0);
            }
            scanBuildingForVictims(mfb.getPosition());
        }
        if (mfb.getAction() == MessageFireBrigade.ACTION_RESCUE && mfb.getTargetID() != null) {
            scanBuildingForVictims(mfb.getTargetID());
        }
    }
    
    private void scanBuildingForVictims(EntityID buildingId) {
        if (buildingId == null) return;
        StandardEntity entity = worldInfo.getEntity(buildingId);
        if (!(entity instanceof Building)) return;
        Collection<StandardEntity> entities = worldInfo.getObjectsInRange(buildingId, 0);
        for (StandardEntity e : entities) {
            if (e instanceof Civilian) {
                Civilian c = (Civilian) e;
                if (c.isPositionDefined() && c.getPosition().equals(buildingId)) {
                    if (c.isHPDefined() && c.getHP() > 0 && c.isBuriednessDefined() && c.getBuriedness() > 0) {
                        if (knownBuriedVictims.add(c.getID())) {
                            int now = this.agentInfo.getTime();
                            targetStartTime.put(c.getID(), now);
                            targetLastProgress.put(c.getID(), now);
                            targetLastBuriedness.put(c.getID(), c.getBuriedness());
                        }
                    }
                }
            }
        }
    }
    
    private void cleanupVictims() {
        // 清理救护车待装载列表
        List<EntityID> toRemove = new ArrayList<>();
        for (EntityID vid : knownUnburiedVictims) {
            Human victim = (Human) worldInfo.getEntity(vid);
            if (victim == null) {
                toRemove.add(vid);
                loadedVictimsCache.add(vid);
                continue;
            }
            if (victim.isHPDefined() && victim.getHP() == 0) {
                toRemove.add(vid);
                continue;
            }
            if (victim.isDamageDefined() && victim.getDamage() == 0) {
                toRemove.add(vid);
                continue;
            }
            if (!victim.isPositionDefined()) {
                toRemove.add(vid);
                loadedVictimsCache.add(vid);
                continue;
            }
            EntityID pos = victim.getPosition();
            if (pos == null) {
                toRemove.add(vid);
                loadedVictimsCache.add(vid);
                continue;
            }
            StandardEntity posEntity = worldInfo.getEntity(pos);
            if (posEntity != null && posEntity.getStandardURN() == REFUGE) {
                toRemove.add(vid);
                loadedVictimsCache.add(vid);
            }
        }
        knownUnburiedVictims.removeAll(toRemove);
        
        // 清理消防车掩埋列表（同时清理超时跟踪）
        toRemove.clear();
        for (EntityID vid : knownBuriedVictims) {
            Human victim = (Human) worldInfo.getEntity(vid);
            if (victim == null) {
                toRemove.add(vid);
                continue;
            }
            if (victim.isHPDefined() && victim.getHP() == 0) {
                toRemove.add(vid);
                continue;
            }
            if (victim.isBuriednessDefined() && victim.getBuriedness() == 0) {
                toRemove.add(vid);
            }
        }
        for (EntityID vid : toRemove) {
            knownBuriedVictims.remove(vid);
            targetStartTime.remove(vid);
            targetLastProgress.remove(vid);
            targetLastBuriedness.remove(vid);
        }
        
        // 清理 loadedVictimsCache 中的无效条目
        loadedVictimsCache.removeIf(vid -> {
            Human h = (Human) worldInfo.getEntity(vid);
            if (h == null) return true;
            if (h.isHPDefined() && h.getHP() == 0) return true;
            if (!h.isPositionDefined()) return true;
            EntityID pos = h.getPosition();
            if (pos == null) return true;
            StandardEntity posEntity = worldInfo.getEntity(pos);
            return posEntity != null && posEntity.getStandardURN() == REFUGE;
        });
    }
    
    @Override
    public HumanDetector calc() {
        try {
            this.result = null;
            
            if (this.agentType == AMBULANCE_TEAM) {
                // 优先选择已知未装载伤员
                if (!knownUnburiedVictims.isEmpty()) {
                    Set<EntityID> validTargets = new HashSet<>();
                    for (EntityID vid : knownUnburiedVictims) {
                        Human h = (Human) worldInfo.getEntity(vid);
                        if (h == null || !(h instanceof Civilian)) continue;
                        if (h.isHPDefined() && h.getHP() == 0) continue;
                        if (!h.isPositionDefined()) continue;
                        EntityID pos = h.getPosition();
                        if (pos == null) continue;
                        StandardEntity posEntity = worldInfo.getEntity(pos);
                        if (posEntity != null && posEntity.getStandardURN() == REFUGE) continue;
                        if (h.isDamageDefined() && h.getDamage() == 0) continue;
                        validTargets.add(vid);
                    }
                    if (!validTargets.isEmpty()) {
                        EntityID nearest = findNearestVictim(validTargets);
                        if (nearest != null) {
                            this.result = nearest;
                            System.err.println("[HumanDetector] 救护车 " + agentInfo.getID() + 
                                " 选择待装载平民: " + nearest);
                            return this;
                        }
                    }
                }
                
                // 附近直接可见的伤员
                EntityID currentPos = this.agentInfo.getPosition();
                for (StandardEntity e : this.worldInfo.getObjectsInRange(currentPos, 100)) {
                    if (e instanceof Civilian) {
                        Civilian c = (Civilian) e;
                        if (!c.isPositionDefined() || !c.getPosition().equals(currentPos)) continue;
                        boolean isBuried = c.isBuriednessDefined() && c.getBuriedness() > 0;
                        boolean hasDamage = c.isDamageDefined() && c.getDamage() > 0;
                        if (isBuried) {
                            if (!reportedVictims.contains(c.getID())) {
                                sendReportMessage(c);
                                reportedVictims.add(c.getID());
                            }
                            continue;
                        } else if (hasDamage) {
                            EntityID pos = c.getPosition();
                            if (pos != null) {
                                StandardEntity posEntity = worldInfo.getEntity(pos);
                                if (posEntity != null && posEntity.getStandardURN() == REFUGE) continue;
                            }
                            this.result = c.getID();
                            System.err.println("[HumanDetector] 救护车 " + agentInfo.getID() + 
                                " 直接发现附近伤员: " + c.getID());
                            return this;
                        }
                    }
                }
                return this;
            }
            
            if (this.agentType == FIRE_BRIGADE) {
                // 优先处理已知掩埋目标
                if (!knownBuriedVictims.isEmpty()) {
                    Set<EntityID> activeVictims = new HashSet<>();
                    for (EntityID vid : knownBuriedVictims) {
                        Human h = (Human) worldInfo.getEntity(vid);
                        if (h != null && h.isBuriednessDefined() && h.getBuriedness() > 0) {
                            activeVictims.add(vid);
                        }
                    }
                    Set<EntityID> civilians = new HashSet<>();
                    for (EntityID vid : activeVictims) {
                        if (worldInfo.getEntity(vid) instanceof Civilian) {
                            civilians.add(vid);
                        }
                    }
                    EntityID best = !civilians.isEmpty() ? findNearestVictim(civilians) : findNearestVictim(activeVictims);
                    if (best != null) {
                        this.result = best;
                        if (!targetStartTime.containsKey(best)) {
                            int now = this.agentInfo.getTime();
                            targetStartTime.put(best, now);
                            targetLastProgress.put(best, now);
                            Human h = (Human) worldInfo.getEntity(best);
                            targetLastBuriedness.put(best, h != null && h.isBuriednessDefined() ? h.getBuriedness() : 0);
                        }
                        if (!reportedVictims.contains(best)) {
                            sendReportMessage((Human) worldInfo.getEntity(best));
                            reportedVictims.add(best);
                            // 通知警察开路
                            if (worldInfo.getEntity(best) instanceof Human) {
                                Human victim = (Human) worldInfo.getEntity(best);
                                if (victim.isPositionDefined() && this.msgManager != null) {
                                    EntityID buildingId = victim.getPosition();
                                    if (buildingId != null) {
                                        MessageFireBrigade roadRequest = new MessageFireBrigade(
                                            true, (FireBrigade) this.agentInfo.me(),
                                            MessageFireBrigade.ACTION_RESCUE, buildingId);
                                        this.msgManager.addMessage(roadRequest);
                                    }
                                }
                            }
                        }
                        return this;
                    }
                }
                
                // 无已知目标时使用聚类或全局搜索
                if (clustering == null) {
                    this.result = this.calcTargetInWorld();
                } else {
                    this.result = this.calcTargetInCluster(clustering);
                    if (this.result == null) {
                        this.result = this.calcTargetInWorld();
                    }
                }
                if (this.result != null) {
                    Human h = (Human) worldInfo.getEntity(this.result);
                    if (h != null && !reportedVictims.contains(this.result)) {
                        sendReportMessage(h);
                        reportedVictims.add(this.result);
                    }
                }
            }
        } catch (Exception e) {
            this.result = null;
        }
        return this;
    }
    
    private double getDistanceToVictim(EntityID victimId) {
        Human h = (Human) worldInfo.getEntity(victimId);
        if (h == null || !h.isPositionDefined()) return Double.MAX_VALUE;
        EntityID hPos = h.getPosition();
        if (hPos == null) return Double.MAX_VALUE;
        return this.worldInfo.getDistance(this.agentInfo.getPosition(), hPos);
    }
    
    private EntityID findNearestVictim(Set<EntityID> victimSet) {
        EntityID nearest = null;
        double minDist = Double.MAX_VALUE;
        for (EntityID vid : victimSet) {
            double dist = getDistanceToVictim(vid);
            if (dist < minDist) {
                minDist = dist;
                nearest = vid;
            }
        }
        return nearest;
    }
    
    private void sendReportMessage(Human human) {
        if (this.msgManager == null) return;
        if (!human.isPositionDefined()) return;
        EntityID pos = human.getPosition();
        if (pos == null) return;
        if (human instanceof Civilian) {
            MessageCivilian msg = new MessageCivilian(true, (Civilian) human);
            this.msgManager.addMessage(msg);
        } else if (human instanceof PoliceForce) {
            MessagePoliceForce msg = new MessagePoliceForce(true, (PoliceForce) human, 
                                                           MessagePoliceForce.ACTION_REST, pos);
            this.msgManager.addMessage(msg);
        } else if (human instanceof FireBrigade) {
            MessageFireBrigade msg = new MessageFireBrigade(true, (FireBrigade) human, 
                                                            MessageFireBrigade.ACTION_REST, pos);
            this.msgManager.addMessage(msg);
        } else if (human instanceof AmbulanceTeam) {
            MessageAmbulanceTeam msg = new MessageAmbulanceTeam(true, (AmbulanceTeam) human, 
                                                                MessageAmbulanceTeam.ACTION_REST, pos);
            this.msgManager.addMessage(msg);
        }
    }
    
    private EntityID calcTargetInCluster(Clustering clustering) {
        int clusterIndex = clustering.getClusterIndex(this.agentInfo.getID());
        Collection<StandardEntity> elements = clustering.getClusterEntities(clusterIndex);
        if (elements == null || elements.isEmpty()) return null;
        Set<EntityID> civilians = new HashSet<>();
        Set<EntityID> others = new HashSet<>();
        for (StandardEntity next : this.worldInfo.getEntitiesOfType(CIVILIAN, POLICE_FORCE, 
                                                                    FIRE_BRIGADE, AMBULANCE_TEAM)) {
            Human h = (Human) next;
            if (!h.isPositionDefined()) continue;
            EntityID hPos = h.getPosition();
            if (hPos == null) continue;
            StandardEntity positionEntity = this.worldInfo.getPosition(h);
            if (positionEntity != null && elements.contains(positionEntity)) {
                if (h.isHPDefined() && h.getHP() > 0 && h.isBuriednessDefined() && h.getBuriedness() > 0) {
                    if (h instanceof Civilian) civilians.add(h.getID());
                    else others.add(h.getID());
                }
            }
        }
        if (!civilians.isEmpty()) return findNearestVictim(civilians);
        if (!others.isEmpty()) return findNearestVictim(others);
        return null;
    }

    private EntityID calcTargetInWorld() {
        Set<EntityID> civilians = new HashSet<>();
        Set<EntityID> others = new HashSet<>();
        for (StandardEntity next : this.worldInfo.getEntitiesOfType(CIVILIAN, POLICE_FORCE, 
                                                                    FIRE_BRIGADE, AMBULANCE_TEAM)) {
            Human h = (Human) next;
            if (!h.isPositionDefined()) continue;
            if (h.isHPDefined() && h.getHP() > 0 && h.isBuriednessDefined() && h.getBuriedness() > 0) {
                if (h instanceof Civilian) civilians.add(h.getID());
                else others.add(h.getID());
            }
        }
        if (!civilians.isEmpty()) return findNearestVictim(civilians);
        if (!others.isEmpty()) return findNearestVictim(others);
        return null;
    }

    @Override
    public EntityID getTarget() { return this.result; }
    
    @Override
    public HumanDetector precompute(PrecomputeData pd) { return this; }
    @Override
    public HumanDetector resume(PrecomputeData pd) { return this; }
    @Override
    public HumanDetector preparate() { return this; }
}