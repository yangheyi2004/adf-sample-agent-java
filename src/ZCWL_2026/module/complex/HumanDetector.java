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
    private LinkedHashSet<EntityID> knownBuriedVictims;   // 消防车
    private Set<EntityID> knownUnburiedVictims;           // 救护车
    private StandardEntityURN agentType;
    
    private int lastLogTime;
    private static final int LOG_INTERVAL = 3;
    
    private int lastFullScanTime = 0;
    private static final int FULL_SCAN_INTERVAL = 5;

    private Set<EntityID> loadedVictimsCache;

    private Map<EntityID, Integer> targetStartTime;
    private Map<EntityID, Integer> targetLastProgress;
    private Map<EntityID, Integer> targetLastBuriedness;
    private static final int TARGET_TIMEOUT = 65;

    private Map<EntityID, Integer> cooldownVictims;
    private static final int COOLDOWN_DURATION = 30;

    private Set<EntityID> notifiedUnburiedVictims = new HashSet<>();

    public HumanDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                         ModuleManager mm, DevelopData dd) {
        super(ai, wi, si, mm, dd);
        this.knownBuriedVictims = new LinkedHashSet<>();
        this.knownUnburiedVictims = new HashSet<>();
        this.reportedVictims = new HashSet<>();
        this.agentType = ai.me().getStandardURN();
        this.lastLogTime = 0;
        this.loadedVictimsCache = new HashSet<>();

        this.targetStartTime = new HashMap<>();
        this.targetLastProgress = new HashMap<>();
        this.targetLastBuriedness = new HashMap<>();
        this.cooldownVictims = new HashMap<>();

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
        
        // ========== 消防车：世界变化扫描 ==========
        if (this.agentType == FIRE_BRIGADE) {
            for (EntityID id : worldInfo.getChanged().getChangedEntities()) {
                StandardEntity e = worldInfo.getEntity(id);
                if (e instanceof Civilian) {
                    Civilian c = (Civilian) e;
                    if (c.isHPDefined() && c.getHP() > 0 
                        && c.isBuriednessDefined() && c.getBuriedness() > 0
                        && c.isPositionDefined()) {
                        if (!cooldownVictims.containsKey(c.getID()) && knownBuriedVictims.add(c.getID())) {
                            targetStartTime.put(c.getID(), currentTime);
                            targetLastProgress.put(c.getID(), currentTime);
                            targetLastBuriedness.put(c.getID(), c.getBuriedness());
                        }
                    }
                }
            }
            
            if (this.result != null && knownBuriedVictims.contains(this.result)) {
                Human victim = (Human) this.worldInfo.getEntity(this.result);
                if (victim != null && victim.isBuriednessDefined()) {
                    int oldBuried = targetLastBuriedness.getOrDefault(this.result, -1);
                    int newBuried = victim.getBuriedness();
                    if (oldBuried > newBuried && newBuried >= 0) {
                        targetLastProgress.put(this.result, currentTime);
                        targetLastBuriedness.put(this.result, newBuried);
                    } else if (oldBuried == -1) {
                        targetLastBuriedness.put(this.result, newBuried);
                        targetLastProgress.put(this.result, currentTime);
                    }
                }
            }
            
            List<EntityID> timeoutVictims = new ArrayList<>();
            for (EntityID vid : knownBuriedVictims) {
                Integer lastProg = targetLastProgress.get(vid);
                if (lastProg == null) continue;
                if (currentTime - lastProg > TARGET_TIMEOUT) {
                    timeoutVictims.add(vid);
                }
            }
            for (EntityID vid : timeoutVictims) {
                knownBuriedVictims.remove(vid);
                targetStartTime.remove(vid);
                targetLastProgress.remove(vid);
                targetLastBuriedness.remove(vid);
                cooldownVictims.put(vid, currentTime + COOLDOWN_DURATION);
                if (this.result != null && this.result.equals(vid)) {
                    this.result = null;
                }
            }

            List<EntityID> readyToRejoin = new ArrayList<>();
            for (Map.Entry<EntityID, Integer> entry : cooldownVictims.entrySet()) {
                if (currentTime >= entry.getValue()) {
                    readyToRejoin.add(entry.getKey());
                }
            }
            for (EntityID vid : readyToRejoin) {
                cooldownVictims.remove(vid);
                Human h = (Human) this.worldInfo.getEntity(vid);
                if (h != null && h.isBuriednessDefined() && h.getBuriedness() > 0 && h instanceof Civilian) {
                    knownBuriedVictims.add(vid);
                    targetStartTime.put(vid, currentTime);
                    targetLastProgress.put(vid, currentTime);
                    targetLastBuriedness.put(vid, h.getBuriedness());
                }
            }
        }
        
        // ========== 救护车：本地感知 ==========
        if (this.agentType == AMBULANCE_TEAM) {
            for (EntityID id : worldInfo.getChanged().getChangedEntities()) {
                StandardEntity e = worldInfo.getEntity(id);
                if (e instanceof Civilian) {
                    Civilian c = (Civilian) e;
                    if (c.isHPDefined() && c.getHP() > 0 
                        && c.isBuriednessDefined() && c.getBuriedness() == 0
                        && c.isDamageDefined() && c.getDamage() > 0
                        && c.isPositionDefined()) {
                        if (!loadedVictimsCache.contains(c.getID())) {
                            knownUnburiedVictims.add(c.getID());
                        }
                    }
                }
            }

            if (currentTime - lastFullScanTime >= FULL_SCAN_INTERVAL) {
                lastFullScanTime = currentTime;
                cleanupVictims();
                for (StandardEntity e : this.worldInfo.getEntitiesOfType(CIVILIAN)) {
                    Civilian c = (Civilian) e;
                    if (c.isHPDefined() && c.getHP() > 0 
                        && c.isBuriednessDefined() && c.getBuriedness() == 0
                        && c.isDamageDefined() && c.getDamage() > 0
                        && c.isPositionDefined()) {
                        if (!loadedVictimsCache.contains(c.getID())) {
                            knownUnburiedVictims.add(c.getID());
                        }
                    }
                }
            }
        }
        
        // ========== 消息处理 ==========
        for (CommunicationMessage msg : mm.getReceivedMessageList()) {
            if (msg instanceof MessageCivilian) {
                MessageCivilian mc = (MessageCivilian) msg;
                EntityID victimId = mc.getAgentID();
                
                if (mc.isRadio() && mc.isBuriednessDefined() && mc.getBuriedness() == 0) {
                    if (this.agentType == AMBULANCE_TEAM) {
                        StandardEntity e = worldInfo.getEntity(victimId);
                        if (e instanceof Civilian) {
                            Civilian c = (Civilian) e;
                            if (c.isPositionDefined() && !loadedVictimsCache.contains(victimId)) {
                                knownUnburiedVictims.add(victimId);
                                System.err.println("[HumanDetector] 救护车 " + agentInfo.getID() + 
                                    " 收到挖出通知，加入待装载: " + victimId + 
                                    " damage=" + (c.isDamageDefined() ? c.getDamage() : "?"));
                            }
                        }
                    }
                    if (this.agentType == FIRE_BRIGADE) {
                        knownBuriedVictims.remove(victimId);
                        cooldownVictims.remove(victimId);
                    }
                }
                
                if (this.agentType == FIRE_BRIGADE && mc.isBuriednessDefined() && mc.getBuriedness() > 0) {
                    if (!cooldownVictims.containsKey(victimId)) {
                        knownBuriedVictims.add(victimId);
                        targetStartTime.put(victimId, currentTime);
                        targetLastProgress.put(victimId, currentTime);
                        targetLastBuriedness.put(victimId, mc.getBuriedness());
                    }
                }
                processCivilianMessage(mc);
                
            } else if (msg instanceof MessageFireBrigade) {
                processFireBrigadeMessage((MessageFireBrigade) msg);
            } else if (msg instanceof MessagePoliceForce) {
                MessagePoliceForce mpf = (MessagePoliceForce) msg;
                if (this.agentType == FIRE_BRIGADE && mpf.isBuriednessDefined() && mpf.getBuriedness() > 0) {
                    if (!cooldownVictims.containsKey(mpf.getAgentID())) {
                        knownBuriedVictims.add(mpf.getAgentID());
                        targetStartTime.put(mpf.getAgentID(), currentTime);
                        targetLastProgress.put(mpf.getAgentID(), currentTime);
                        targetLastBuriedness.put(mpf.getAgentID(), 0);
                    }
                    scanBuildingForVictims(mpf.getPosition());
                }
            } else if (msg instanceof MessageAmbulanceTeam) {
                MessageAmbulanceTeam mat = (MessageAmbulanceTeam) msg;
                if (this.agentType == FIRE_BRIGADE && mat.isBuriednessDefined() && mat.getBuriedness() > 0) {
                    if (!cooldownVictims.containsKey(mat.getAgentID())) {
                        knownBuriedVictims.add(mat.getAgentID());
                        targetStartTime.put(mat.getAgentID(), currentTime);
                        targetLastProgress.put(mat.getAgentID(), currentTime);
                        targetLastBuriedness.put(mat.getAgentID(), 0);
                    }
                    scanBuildingForVictims(mat.getPosition());
                }
                if (this.agentType == AMBULANCE_TEAM) {
                    if (mat.getAction() == MessageAmbulanceTeam.ACTION_LOAD) {
                        EntityID targetId = mat.getTargetID();
                        if (targetId != null) {
                            knownUnburiedVictims.remove(targetId);
                            loadedVictimsCache.add(targetId);
                        }
                    } else if (mat.getAction() == MessageAmbulanceTeam.ACTION_UNLOAD) {
                        EntityID targetId = mat.getTargetID();
                        if (targetId != null) knownUnburiedVictims.remove(targetId);
                    }
                }
            }
        }
        
        cleanupVictims();

        if (currentTime - lastLogTime >= LOG_INTERVAL) {
            lastLogTime = currentTime;
            if (this.agentType == FIRE_BRIGADE) {
                int civilianCount = 0, otherCount = 0;
                for (EntityID vid : knownBuriedVictims) {
                    if (worldInfo.getEntity(vid) instanceof Civilian) civilianCount++;
                    else otherCount++;
                }
                System.err.println("[HumanDetector] 消防车 " + agentInfo.getID() + 
                    " 待救援目标: 平民=" + civilianCount + " 其他=" + otherCount + 
                    " 冷却中=" + cooldownVictims.size());
            } else if (this.agentType == AMBULANCE_TEAM) {
                System.err.println("[HumanDetector] 救护车 " + agentInfo.getID() + 
                    " 待装载伤员: " + knownUnburiedVictims.size());
            }
        }
        return this;
    }
    
    private void processCivilianMessage(MessageCivilian mc) {
        EntityID victimId = mc.getAgentID();
        if (mc.isHPDefined() && mc.getHP() == 0) return;
        boolean isBuried = mc.isBuriednessDefined() && mc.getBuriedness() > 0;
        boolean hasDamage = mc.isDamageDefined() && mc.getDamage() > 0;
        
        if (this.agentType == AMBULANCE_TEAM && !isBuried && hasDamage) {
            if (!loadedVictimsCache.contains(victimId))
                knownUnburiedVictims.add(victimId);
        } else if (this.agentType == FIRE_BRIGADE && isBuried) {
            if (!cooldownVictims.containsKey(victimId)) {
                knownBuriedVictims.add(victimId);
                targetStartTime.put(victimId, agentInfo.getTime());
                targetLastProgress.put(victimId, agentInfo.getTime());
                targetLastBuriedness.put(victimId, mc.getBuriedness());
            }
            if (mc.isPositionDefined()) scanBuildingForVictims(mc.getPosition());
        }
    }
    
    private void processFireBrigadeMessage(MessageFireBrigade mfb) {
        if (this.agentType != FIRE_BRIGADE) return;
        if (mfb.isBuriednessDefined() && mfb.getBuriedness() > 0) {
            if (!cooldownVictims.containsKey(mfb.getAgentID())) {
                knownBuriedVictims.add(mfb.getAgentID());
                int now = agentInfo.getTime();
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
        for (StandardEntity e : worldInfo.getObjectsInRange(buildingId, 0)) {
            if (e instanceof Civilian) {
                Civilian c = (Civilian) e;
                if (c.isPositionDefined() && c.getPosition().equals(buildingId)
                    && c.isHPDefined() && c.getHP() > 0
                    && c.isBuriednessDefined() && c.getBuriedness() > 0) {
                    if (!cooldownVictims.containsKey(c.getID())) {
                        knownBuriedVictims.add(c.getID());
                        int now = agentInfo.getTime();
                        targetStartTime.put(c.getID(), now);
                        targetLastProgress.put(c.getID(), now);
                        targetLastBuriedness.put(c.getID(), c.getBuriedness());
                    }
                }
            }
        }
    }
    
    private void cleanupVictims() {
        List<EntityID> ambulanceRemove = new ArrayList<>();
        for (EntityID vid : knownUnburiedVictims) {
            Human victim = (Human) worldInfo.getEntity(vid);
            if (victim == null) {
                ambulanceRemove.add(vid);
                loadedVictimsCache.add(vid);
                continue;
            }
            if (victim.isHPDefined() && victim.getHP() == 0) {
                ambulanceRemove.add(vid);
                continue;
            }
            // 不再因 damage == 0 移除
            if (!victim.isPositionDefined()) {
                ambulanceRemove.add(vid);
                loadedVictimsCache.add(vid);
                continue;
            }
            EntityID pos = victim.getPosition();
            if (pos == null) {
                ambulanceRemove.add(vid);
                loadedVictimsCache.add(vid);
                continue;
            }
            StandardEntity posEntity = worldInfo.getEntity(pos);
            if (posEntity != null && posEntity.getStandardURN() == REFUGE) {
                ambulanceRemove.add(vid);
                loadedVictimsCache.add(vid);
            }
        }
        knownUnburiedVictims.removeAll(ambulanceRemove);

        List<EntityID> buriedToRemove = new ArrayList<>();
        for (EntityID vid : knownBuriedVictims) {
            Human victim = (Human) worldInfo.getEntity(vid);
            if (victim == null) {
                buriedToRemove.add(vid);
                continue;
            }
            if (victim.isHPDefined() && victim.getHP() == 0) {
                buriedToRemove.add(vid);
                continue;
            }
            if (victim.isBuriednessDefined() && victim.getBuriedness() == 0) {
                buriedToRemove.add(vid);
                if (this.agentType == FIRE_BRIGADE && victim instanceof Civilian) {
                    Civilian c = (Civilian) victim;
                    if (!notifiedUnburiedVictims.contains(c.getID())) {
                        if (msgManager != null) {
                            MessageCivilian msg = new MessageCivilian(true, c);
                            msgManager.addMessage(msg);
                            notifiedUnburiedVictims.add(c.getID());
                            System.err.println("[HumanDetector] 消防车 " + agentInfo.getID() 
                                    + " ⚡ 强制通知救护车（已挖出平民）: " + c.getID());
                        }
                    }
                }
            }
        }

        if (this.agentType == FIRE_BRIGADE && !buriedToRemove.isEmpty()) {
            System.err.println("[HumanDetector] 消防车 " + agentInfo.getID() 
                    + " 正在从待救援列表移除 " + buriedToRemove.size() + " 个目标:");
            for (EntityID vid : buriedToRemove) {
                Human h = (Human) worldInfo.getEntity(vid);
                String reason = "未知";
                if (h == null) reason = "实体消失";
                else if (h.isHPDefined() && h.getHP() == 0) reason = "死亡";
                else if (h.isBuriednessDefined() && h.getBuriedness() == 0) reason = "已挖出";
                System.err.println("[HumanDetector]   移除目标 " + vid + " 原因: " + reason);
            }
        }

        for (EntityID vid : buriedToRemove) {
            knownBuriedVictims.remove(vid);
            targetStartTime.remove(vid);
            targetLastProgress.remove(vid);
            targetLastBuriedness.remove(vid);
        }

        loadedVictimsCache.removeIf(vid -> {
            Human h = (Human) worldInfo.getEntity(vid);
            return h == null || (h.isHPDefined() && h.getHP() == 0) || !h.isPositionDefined()
                || (worldInfo.getEntity(h.getPosition()) != null 
                    && worldInfo.getEntity(h.getPosition()).getStandardURN() == REFUGE);
        });

        if (this.agentInfo.getTime() % 100 == 0) {
            notifiedUnburiedVictims.removeIf(vid -> {
                StandardEntity e = worldInfo.getEntity(vid);
                return e == null || !(e instanceof Civilian);
            });
        }
    }
    
    @Override
    public HumanDetector calc() {
        try {
            this.result = null;

            if (this.agentType == AMBULANCE_TEAM) {
                // 统一装载：所有待装载伤员均视为运载目标，不论伤害值
                if (!knownUnburiedVictims.isEmpty()) {
                    Set<EntityID> valid = new HashSet<>();
                    for (EntityID vid : knownUnburiedVictims) {
                        Human h = (Human) worldInfo.getEntity(vid);
                        if (h != null && h instanceof Civilian && h.isPositionDefined()
                                && h.isHPDefined() && h.getHP() > 0
                                && worldInfo.getEntity(h.getPosition()).getStandardURN() != REFUGE) {
                            valid.add(vid);
                        }
                    }
                    if (!valid.isEmpty()) {
                        this.result = findNearestVictim(valid);
                        if (this.result != null) {
                            System.err.println("[HumanDetector] 救护车 " + agentInfo.getID() 
                                    + " 选择装载伤员: " + this.result);
                            return this;
                        }
                    }
                }

                // 附近直接可见的伤员
                EntityID currentPos = this.agentInfo.getPosition();
                for (StandardEntity e : this.worldInfo.getObjectsInRange(currentPos, 100)) {
                    if (e instanceof Civilian) {
                        Civilian c = (Civilian) e;
                        if (c.isPositionDefined() && c.getPosition().equals(currentPos)
                                && c.isHPDefined() && c.getHP() > 0
                                && worldInfo.getEntity(currentPos).getStandardURN() != REFUGE) {
                            this.result = c.getID();
                            System.err.println("[HumanDetector] 救护车 " + agentInfo.getID() 
                                    + " 直接发现附近伤员: " + c.getID());
                            return this;
                        }
                    }
                }
                return this;
            }

            if (this.agentType == FIRE_BRIGADE) {
                if (!knownBuriedVictims.isEmpty()) {
                    EntityID myPos = this.agentInfo.getPosition();
                    List<EntityID> civilians = new ArrayList<>();
                    List<EntityID> others = new ArrayList<>();
                    for (EntityID vid : knownBuriedVictims) {
                        Human h = (Human) worldInfo.getEntity(vid);
                        if (h != null && h.isBuriednessDefined() && h.getBuriedness() > 0) {
                            if (h instanceof Civilian) civilians.add(vid);
                            else others.add(vid);
                        }
                    }
                    EntityID chosen = null;
                    if (!civilians.isEmpty()) {
                        civilians.sort(Comparator.comparingDouble(v -> getDistanceToBuilding(v, myPos)));
                        chosen = civilians.get(0);
                    } else if (!others.isEmpty()) {
                        others.sort(Comparator.comparingDouble(v -> getDistanceToBuilding(v, myPos)));
                        chosen = others.get(0);
                    }
                    if (chosen != null) {
                        this.result = chosen;
                        selectAndReportResult(chosen);
                        return this;
                    }
                }

                if (clustering != null) {
                    this.result = calcTargetInCluster(clustering);
                }
                if (this.result == null) {
                    this.result = calcTargetInWorld();
                }
                if (this.result != null && !reportedVictims.contains(this.result)) {
                    sendReportMessage((Human) worldInfo.getEntity(this.result));
                    reportedVictims.add(this.result);
                }
            }
        } catch (Exception e) {
            this.result = null;
        }
        return this;
    }
    
    private double getDistanceToBuilding(EntityID victimId, EntityID myPos) {
        Human h = (Human) worldInfo.getEntity(victimId);
        if (h == null || !h.isPositionDefined()) return Double.MAX_VALUE;
        return worldInfo.getDistance(myPos, h.getPosition());
    }

    private void selectAndReportResult(EntityID vid) {
        Human h = (Human) worldInfo.getEntity(vid);
        if (h == null) return;
        if (!targetStartTime.containsKey(vid)) {
            int now = agentInfo.getTime();
            targetStartTime.put(vid, now);
            targetLastProgress.put(vid, now);
            targetLastBuriedness.put(vid, h.getBuriedness());
        }
        if (!reportedVictims.contains(vid)) {
            sendReportMessage(h);
            reportedVictims.add(vid);
            if (h.isPositionDefined() && msgManager != null) {
                EntityID bid = h.getPosition();
                if (bid != null) {
                    msgManager.addMessage(new MessageFireBrigade(true, (FireBrigade) agentInfo.me(),
                        MessageFireBrigade.ACTION_RESCUE, bid));
                }
            }
        }
    }
    
    private EntityID findNearestVictim(Set<EntityID> victims) {
        EntityID best = null;
        double min = Double.MAX_VALUE;
        EntityID pos = agentInfo.getPosition();
        for (EntityID vid : victims) {
            Human h = (Human) worldInfo.getEntity(vid);
            if (h != null && h.isPositionDefined()) {
                double d = worldInfo.getDistance(pos, h.getPosition());
                if (d < min) { min = d; best = vid; }
            }
        }
        return best;
    }
    
    private void sendReportMessage(Human human) {
        if (msgManager == null || !human.isPositionDefined()) return;
        EntityID pos = human.getPosition();
        if (pos == null) return;
        if (human instanceof Civilian)
            msgManager.addMessage(new MessageCivilian(true, (Civilian) human));
        else if (human instanceof PoliceForce)
            msgManager.addMessage(new MessagePoliceForce(true, (PoliceForce) human, MessagePoliceForce.ACTION_REST, pos));
        else if (human instanceof FireBrigade)
            msgManager.addMessage(new MessageFireBrigade(true, (FireBrigade) human, MessageFireBrigade.ACTION_REST, pos));
        else if (human instanceof AmbulanceTeam)
            msgManager.addMessage(new MessageAmbulanceTeam(true, (AmbulanceTeam) human, MessageAmbulanceTeam.ACTION_REST, pos));
    }
    
    private EntityID calcTargetInCluster(Clustering clustering) {
        int idx = clustering.getClusterIndex(agentInfo.getID());
        Collection<StandardEntity> elements = clustering.getClusterEntities(idx);
        if (elements == null || elements.isEmpty()) return null;
        Set<EntityID> civilians = new HashSet<>(), others = new HashSet<>();
        for (StandardEntity e : worldInfo.getEntitiesOfType(CIVILIAN, POLICE_FORCE, FIRE_BRIGADE, AMBULANCE_TEAM)) {
            Human h = (Human) e;
            if (!h.isPositionDefined() || !h.isHPDefined()) continue;
            if (elements.contains(worldInfo.getPosition(h))) {
                if (h.getHP() > 0 && h.isBuriednessDefined() && h.getBuriedness() > 0) {
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
        Set<EntityID> civilians = new HashSet<>(), others = new HashSet<>();
        for (StandardEntity e : worldInfo.getEntitiesOfType(CIVILIAN, POLICE_FORCE, FIRE_BRIGADE, AMBULANCE_TEAM)) {
            Human h = (Human) e;
            if (!h.isPositionDefined() || !h.isHPDefined()) continue;
            if (h.getHP() > 0 && h.isBuriednessDefined() && h.getBuriedness() > 0) {
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