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
    private Set<EntityID> loggedCivilianMessages = new HashSet<>();

    // ========== 新增：救护车全量扫描计时 ==========
    private int lastFullScanTime = 0;
    private static final int FULL_SCAN_INTERVAL = 3; // 每7步全量扫描一次

    public HumanDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                         ModuleManager mm, DevelopData dd) {
        super(ai, wi, si, mm, dd);
        this.knownBuriedVictims = new HashSet<>();
        this.knownUnburiedVictims = new HashSet<>();
        this.reportedVictims = new HashSet<>();
        this.agentType = ai.me().getStandardURN();
        this.lastLogTime = 0;

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
        
        // ========== 主动扫描世界变化 ==========
        if (this.agentType == FIRE_BRIGADE) {
            for (EntityID id : worldInfo.getChanged().getChangedEntities()) {
                StandardEntity e = worldInfo.getEntity(id);
                if (e instanceof Civilian) {
                    Civilian c = (Civilian) e;
                    if (c.isHPDefined() && c.getHP() > 0 
                        && c.isBuriednessDefined() && c.getBuriedness() > 0
                        && c.isPositionDefined()) {
                        if (knownBuriedVictims.add(c.getID())) {
                            /*System.err.println("[HumanDetector] 世界变化扫描发现被掩埋平民: " + c.getID() +
                                               " 于建筑 " + c.getPosition());*/
                        }
                    }
                }
            }
        }
        
        // ========== 救护车：主动扫描被挖出的平民 ==========
        if (this.agentType == AMBULANCE_TEAM) {
            for (EntityID id : worldInfo.getChanged().getChangedEntities()) {
                StandardEntity e = worldInfo.getEntity(id);
                if (e instanceof Civilian) {
                    Civilian c = (Civilian) e;
                    // 已挖出且受伤的平民加入待装载列表
                    if (c.isHPDefined() && c.getHP() > 0 
                        && c.isBuriednessDefined() && c.getBuriedness() == 0
                        && c.isDamageDefined() && c.getDamage() > 0
                        && c.isPositionDefined()) {
                        if (knownUnburiedVictims.add(c.getID())) {
                           /*  System.err.println("[HumanDetector] 救护车扫描到已挖出伤员: " + c.getID() +
                                               " 伤害=" + c.getDamage() + " 位置=" + c.getPosition());*/
                        }
                    }
                }
            }

            // ========== 新增：定期全量扫描所有平民，防止遗漏 ==========
            int currentTime = this.agentInfo.getTime();
            if (currentTime - lastFullScanTime >= FULL_SCAN_INTERVAL) {
                lastFullScanTime = currentTime;
                for (StandardEntity e : this.worldInfo.getEntitiesOfType(CIVILIAN)) {
                    Civilian c = (Civilian) e;
                    if (c.isHPDefined() && c.getHP() > 0 
                        && c.isBuriednessDefined() && c.getBuriedness() == 0
                        && c.isDamageDefined() && c.getDamage() > 0
                        && c.isPositionDefined()) {
                        if (knownUnburiedVictims.add(c.getID())) {
                            System.err.println("[HumanDetector] 救护车全量扫描发现已挖出伤员: " + c.getID() +
                                               " 伤害=" + c.getDamage() + " 位置=" + c.getPosition());
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
                String source = mc.isRadio() ? "语音" : "无线";
                
                // ========== 新增：语音消息中已挖出且受伤的平民，加入待装载列表 ==========
                if (mc.isRadio() && mc.isBuriednessDefined() && mc.getBuriedness() == 0
                        && mc.isDamageDefined() && mc.getDamage() > 0) {
                    if (this.agentType == AMBULANCE_TEAM) {
                        // 检查平民是否仍然有效（未被装载、未死亡、未治愈）
                        StandardEntity e = worldInfo.getEntity(victimId);
                        if (e instanceof Civilian) {
                            Civilian c = (Civilian) e;
                            if (c.isPositionDefined() && c.getPosition() != null) {
                                if (knownUnburiedVictims.add(victimId)) {
                                    ///System.err.println("[HumanDetector] 🚑 收到语音挖出通知，添加已挖出伤员: " + victimId);
                                }
                            } else {
                                // 位置无效，说明已被装载，从待装载列表中移除
                                if (knownUnburiedVictims.remove(victimId)) {
                                    System.err.println("[HumanDetector] 🚑 收到语音装载通知，立即移除平民: " + victimId);
                                }
                            }
                        }
                    }
                    // 同时从消防车的掩埋列表中移除（如果存在）
                    knownBuriedVictims.remove(victimId);
                    // 不 continue，让后续 processCivilianMessage 也处理一遍（Set 会去重）
                }
                
                if (this.agentType == FIRE_BRIGADE && !loggedCivilianMessages.contains(victimId)) {
                    /*System.err.println("[HumanDetector] 收到 MessageCivilian [" + source + "]: agent=" + victimId + 
                                       ", buriedness=" + (mc.isBuriednessDefined() ? mc.getBuriedness() : "undefined") +
                                       ", damage=" + (mc.isDamageDefined() ? mc.getDamage() : "undefined") +
                                       ", position=" + mc.getPosition() +
                                       ", HP=" + (mc.isHPDefined() ? mc.getHP() : "undefined"));*/
                    loggedCivilianMessages.add(victimId);
                }
                processCivilianMessage(mc);
                
            } else if (msg instanceof MessageFireBrigade) {
                processFireBrigadeMessage((MessageFireBrigade) msg);
            } else if (msg instanceof MessagePoliceForce) {
                MessagePoliceForce mpf = (MessagePoliceForce) msg;
                if (this.agentType == FIRE_BRIGADE && mpf.isBuriednessDefined() && mpf.getBuriedness() > 0) {
                    knownBuriedVictims.add(mpf.getAgentID());
                    scanBuildingForVictims(mpf.getPosition());
                }
            } else if (msg instanceof MessageAmbulanceTeam) {
                MessageAmbulanceTeam mat = (MessageAmbulanceTeam) msg;
                if (this.agentType == FIRE_BRIGADE && mat.isBuriednessDefined() && mat.getBuriedness() > 0) {
                    knownBuriedVictims.add(mat.getAgentID());
                    scanBuildingForVictims(mat.getPosition());
                }
            }
        }
        
        cleanupVictims();
        
        int currentTimeLog = this.agentInfo.getTime();
        if (currentTimeLog - lastLogTime >= LOG_INTERVAL) {
            lastLogTime = currentTimeLog;
            logPendingVictims();
        }
        
        return this;
    }
    
    private void processCivilianMessage(MessageCivilian mc) {
        EntityID victimId = mc.getAgentID();
        if (mc.isHPDefined() && mc.getHP() == 0) {
            return;
        }
        boolean isBuried = mc.isBuriednessDefined() && mc.getBuriedness() > 0;
        boolean hasDamage = mc.isDamageDefined() && mc.getDamage() > 0;
        
        if (this.agentType == AMBULANCE_TEAM) {
            if (!isBuried && hasDamage) {
                StandardEntity e = worldInfo.getEntity(victimId);
                if (e instanceof Civilian) {
                    knownUnburiedVictims.add(victimId);
                }
            }
        } else if (this.agentType == FIRE_BRIGADE) {
            if (isBuried) {
                knownBuriedVictims.add(victimId);
                if (mc.isPositionDefined()) {
                    scanBuildingForVictims(mc.getPosition());
                }
            }
        }
    }
    
    private void processFireBrigadeMessage(MessageFireBrigade mfb) {
        if (this.agentType != FIRE_BRIGADE) return;
        if (mfb.isBuriednessDefined() && mfb.getBuriedness() > 0) {
            knownBuriedVictims.add(mfb.getAgentID());
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
                        knownBuriedVictims.add(c.getID());
                    }
                }
            }
        }
    }
    
    private void logPendingVictims() {
        String agentName = (this.agentType == FIRE_BRIGADE) ? "消防车" : "救护车";
        if (this.agentType == FIRE_BRIGADE) {
            //System.err.println("[" + agentName + " ID:" + this.agentInfo.getID() + 
                               //"] 📊 待救援: 被掩埋=" + knownBuriedVictims.size());
        } else {
            //System.err.println("[" + agentName + " ID:" + this.agentInfo.getID() + 
                               //"] 📊 待装载: 已挖出=" + knownUnburiedVictims.size());
        }
    }
    
    private void cleanupVictims() {
        List<EntityID> toRemove = new ArrayList<>();
        for (EntityID vid : knownUnburiedVictims) {
            Human victim = (Human) worldInfo.getEntity(vid);
            if (victim == null) {
                toRemove.add(vid);
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
                continue;
            }
            EntityID pos = victim.getPosition();
            if (pos == null) {
                toRemove.add(vid);
                continue;
            }
            StandardEntity posEntity = worldInfo.getEntity(pos);
            if (posEntity != null && posEntity.getStandardURN() == REFUGE) {
                toRemove.add(vid);
            }
        }
        knownUnburiedVictims.removeAll(toRemove);
        
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
        knownBuriedVictims.removeAll(toRemove);
    }

    @Override
public HumanDetector calc() {
    try {
        this.result = null;
        
        if (this.agentType == AMBULANCE_TEAM) {
            if (!knownUnburiedVictims.isEmpty()) {
                Set<EntityID> validTargets = new HashSet<>();
                for (EntityID vid : knownUnburiedVictims) {
                    Human h = (Human) worldInfo.getEntity(vid);
                    if (h == null) continue;
                    if (!(h instanceof Civilian)) continue;
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
                        System.err.println("[救护车] 选择已挖出伤员: " + nearest);
                        return this;
                    }
                }
            }
            
            EntityID currentPos = this.agentInfo.getPosition();
            Collection<StandardEntity> entitiesInRange = this.worldInfo.getObjectsInRange(currentPos, 100);
            for (StandardEntity e : entitiesInRange) {
                if (e instanceof Civilian) {
                    Civilian c = (Civilian) e;
                    EntityID humanId = c.getID();
                    if (!c.isPositionDefined() || !c.getPosition().equals(currentPos)) continue;
                    boolean isBuried = c.isBuriednessDefined() && c.getBuriedness() > 0;
                    boolean hasDamage = c.isDamageDefined() && c.getDamage() > 0;
                    if (isBuried) {
                        if (!reportedVictims.contains(humanId)) {
                            sendReportMessage(c);
                            reportedVictims.add(humanId);
                        }
                        continue;
                    } else if (hasDamage) {
                        EntityID pos = c.getPosition();
                        if (pos != null) {
                            StandardEntity posEntity = worldInfo.getEntity(pos);
                            if (posEntity != null && posEntity.getStandardURN() == REFUGE) {
                                continue;
                            }
                        }
                        this.result = humanId;
                        //System.err.println("[救护车] 发现身边伤员: " + humanId);
                        return this;
                    }
                }
            }
            return this;
        }
        
        if (this.agentType == FIRE_BRIGADE) {
            if (!knownBuriedVictims.isEmpty()) {
                Set<EntityID> civilians = new HashSet<>();
                for (EntityID vid : knownBuriedVictims) {
                    Human h = (Human) worldInfo.getEntity(vid);
                    if (h instanceof Civilian) {
                        civilians.add(vid);
                    }
                }
                EntityID best;
                if (!civilians.isEmpty()) {
                    best = findNearestVictim(civilians);
                } else {
                    best = findNearestVictim(knownBuriedVictims);
                }
                if (best != null) {
                    this.result = best;
                    Human victim = (Human) worldInfo.getEntity(best);
                    if (!reportedVictims.contains(best)) {
                        sendReportMessage(victim);
                        reportedVictims.add(best);
                    }
                    return this;
                }
            }
            
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
        System.err.println("[HumanDetector] calc() 异常: " + e.getMessage());
        e.printStackTrace();
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
                    if (h instanceof Civilian) {
                        civilians.add(h.getID());
                    } else {
                        others.add(h.getID());
                    }
                }
            }
        }
        if (!civilians.isEmpty()) {
            return findNearestVictim(civilians);
        } else if (!others.isEmpty()) {
            return findNearestVictim(others);
        }
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
                if (h instanceof Civilian) {
                    civilians.add(h.getID());
                } else {
                    others.add(h.getID());
                }
            }
        }
        if (!civilians.isEmpty()) {
            return findNearestVictim(civilians);
        } else if (!others.isEmpty()) {
            return findNearestVictim(others);
        }
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