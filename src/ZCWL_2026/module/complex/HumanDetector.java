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
    private Set<EntityID> knownBuriedVictims;      // 消防车：已知被掩埋的单位（平民和其他智能体）
    private Set<EntityID> knownUnburiedVictims;    // 救护车：已挖出待装载的伤员（仅平民）
    private StandardEntityURN agentType;
    
    private int lastLogTime;
    private static final int LOG_INTERVAL = 10;
    private Set<EntityID> loggedCivilianMessages = new HashSet<>();

    // ========== 救护车全量扫描计时 ==========
    private int lastFullScanTime = 0;
    private static final int FULL_SCAN_INTERVAL = 5;

    // ========== 救护车：已装载平民缓存 ==========
    private Set<EntityID> loadedVictimsCache;

    // ========== 消防车：超时放弃 + 失败冷却 ==========
    private Map<EntityID, Integer> targetStartTime;      // 目标开始时间
    private Map<EntityID, Integer> targetLastProgress;   // 最后进展时间
    private Map<EntityID, Integer> targetLastBuriedness; // 上次掩埋度
    private static final int TARGET_TIMEOUT = 60;         // 60步无进展放弃

    private Map<EntityID, Integer> failedTargets;        // 失败的目标 -> 冷却结束时间
    private static final int FAILED_COOLDOWN_STEPS = 30;  // 放弃后30步内不再选择

    public HumanDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                         ModuleManager mm, DevelopData dd) {
        super(ai, wi, si, mm, dd);
        this.knownBuriedVictims = new HashSet<>();
        this.knownUnburiedVictims = new HashSet<>();
        this.reportedVictims = new HashSet<>();
        this.agentType = ai.me().getStandardURN();
        this.lastLogTime = 0;
        this.loadedVictimsCache = new HashSet<>();

        this.targetStartTime = new HashMap<>();
        this.targetLastProgress = new HashMap<>();
        this.targetLastBuriedness = new HashMap<>();
        this.failedTargets = new HashMap<>();

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

        // ========== 消防车部分 ==========
        if (this.agentType == FIRE_BRIGADE) {
            // 1. 扫描世界变化，添加新掩埋目标（跳过冷却中的）
            for (EntityID id : worldInfo.getChanged().getChangedEntities()) {
                StandardEntity e = worldInfo.getEntity(id);
                if (e instanceof Civilian) {
                    Civilian c = (Civilian) e;
                    if (c.isHPDefined() && c.getHP() > 0
                        && c.isBuriednessDefined() && c.getBuriedness() > 0
                        && c.isPositionDefined()) {
                        EntityID victimId = c.getID();
                        if (isInFailedCooldown(victimId)) continue;
                        if (knownBuriedVictims.add(victimId)) {
                            targetStartTime.put(victimId, currentTime);
                            targetLastProgress.put(victimId, currentTime);
                            targetLastBuriedness.put(victimId, c.getBuriedness());
                        }
                    }
                }
            }

            // 2. 检查当前目标的进展（掩埋度是否减小）
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

            // 3. 清理超时目标（60步无进展）并加入失败冷却
            List<EntityID> timeoutList = new ArrayList<>();
            for (EntityID vid : knownBuriedVictims) {
                Integer lastProg = targetLastProgress.get(vid);
                if (lastProg == null) continue;
                if (currentTime - lastProg > TARGET_TIMEOUT) {
                    timeoutList.add(vid);
                    System.err.printf("[HumanDetector] 消防车 %d 放弃目标 %d: 超过 %d 步无进展，加入冷却 %d 步%n",
                        agentInfo.getID().getValue(), vid.getValue(), TARGET_TIMEOUT, FAILED_COOLDOWN_STEPS);
                    failedTargets.put(vid, currentTime + FAILED_COOLDOWN_STEPS);
                }
            }
            for (EntityID vid : timeoutList) {
                knownBuriedVictims.remove(vid);
                targetStartTime.remove(vid);
                targetLastProgress.remove(vid);
                targetLastBuriedness.remove(vid);
                if (this.result != null && this.result.equals(vid)) {
                    this.result = null;
                }
            }

            // 4. 清理过期的失败冷却
            failedTargets.entrySet().removeIf(entry -> currentTime > entry.getValue());
        }

        // ========== 救护车部分（完整保留） ==========
        if (this.agentType == AMBULANCE_TEAM) {
            // 世界变化扫描
            for (EntityID id : worldInfo.getChanged().getChangedEntities()) {
                StandardEntity e = worldInfo.getEntity(id);
                if (e instanceof Civilian) {
                    Civilian c = (Civilian) e;
                    if (c.isHPDefined() && c.getHP() > 0
                        && c.isBuriednessDefined() && c.getBuriedness() == 0
                        && c.isDamageDefined() && c.getDamage() > 0
                        && c.isPositionDefined()) {
                        if (loadedVictimsCache.contains(c.getID())) continue;
                        knownUnburiedVictims.add(c.getID());
                    }
                }
            }
            // 定期全量扫描
            if (currentTime - lastFullScanTime >= FULL_SCAN_INTERVAL) {
                lastFullScanTime = currentTime;
                cleanupVictims();
                for (StandardEntity e : worldInfo.getEntitiesOfType(CIVILIAN)) {
                    Civilian c = (Civilian) e;
                    if (c.isHPDefined() && c.getHP() > 0
                        && c.isBuriednessDefined() && c.getBuriedness() == 0
                        && c.isDamageDefined() && c.getDamage() > 0
                        && c.isPositionDefined()) {
                        if (loadedVictimsCache.contains(c.getID())) continue;
                        knownUnburiedVictims.add(c.getID());
                    }
                }
            }
        }

        // ========== 消息处理（统一） ==========
        for (CommunicationMessage msg : mm.getReceivedMessageList()) {
            if (msg instanceof MessageCivilian) {
                MessageCivilian mc = (MessageCivilian) msg;
                EntityID vid = mc.getAgentID();
                // 语音消息中挖出的平民
                if (mc.isRadio() && mc.isBuriednessDefined() && mc.getBuriedness() == 0
                        && mc.isDamageDefined() && mc.getDamage() > 0) {
                    if (this.agentType == AMBULANCE_TEAM) {
                        StandardEntity e = worldInfo.getEntity(vid);
                        if (e instanceof Civilian) {
                            Civilian c = (Civilian) e;
                            if (c.isPositionDefined() && c.getPosition() != null) {
                                if (!loadedVictimsCache.contains(vid))
                                    knownUnburiedVictims.add(vid);
                            } else {
                                knownUnburiedVictims.remove(vid);
                                loadedVictimsCache.add(vid);
                            }
                        }
                    }
                    knownBuriedVictims.remove(vid);
                }
                // 消防车处理掩埋信息
                if (this.agentType == FIRE_BRIGADE && mc.isBuriednessDefined() && mc.getBuriedness() > 0) {
                    if (!isInFailedCooldown(vid) && knownBuriedVictims.add(vid)) {
                        targetStartTime.put(vid, currentTime);
                        targetLastProgress.put(vid, currentTime);
                        targetLastBuriedness.put(vid, mc.getBuriedness());
                    }
                }
                processCivilianMessage(mc);
            } else if (msg instanceof MessageFireBrigade) {
                processFireBrigadeMessage((MessageFireBrigade) msg);
            } else if (msg instanceof MessagePoliceForce) {
                MessagePoliceForce mpf = (MessagePoliceForce) msg;
                if (this.agentType == FIRE_BRIGADE && mpf.isBuriednessDefined() && mpf.getBuriedness() > 0) {
                    EntityID vid = mpf.getAgentID();
                    if (!isInFailedCooldown(vid) && knownBuriedVictims.add(vid)) {
                        targetStartTime.put(vid, currentTime);
                        targetLastProgress.put(vid, currentTime);
                        targetLastBuriedness.put(vid, 0);
                    }
                    scanBuildingForVictims(mpf.getPosition());
                }
            } else if (msg instanceof MessageAmbulanceTeam) {
                MessageAmbulanceTeam mat = (MessageAmbulanceTeam) msg;
                EntityID vid = mat.getAgentID();
                if (this.agentType == FIRE_BRIGADE && mat.isBuriednessDefined() && mat.getBuriedness() > 0) {
                    if (!isInFailedCooldown(vid) && knownBuriedVictims.add(vid)) {
                        targetStartTime.put(vid, currentTime);
                        targetLastProgress.put(vid, currentTime);
                        targetLastBuriedness.put(vid, 0);
                    }
                    scanBuildingForVictims(mat.getPosition());
                }
                // 救护车装载/卸载
                if (this.agentType == AMBULANCE_TEAM) {
                    if (mat.getAction() == MessageAmbulanceTeam.ACTION_LOAD) {
                        EntityID targetId = mat.getTargetID();
                        if (targetId != null) {
                            knownUnburiedVictims.remove(targetId);
                            loadedVictimsCache.add(targetId);
                        }
                    } else if (mat.getAction() == MessageAmbulanceTeam.ACTION_UNLOAD) {
                        EntityID targetId = mat.getTargetID();
                        if (targetId != null) {
                            knownUnburiedVictims.remove(targetId);
                        }
                    }
                }
            }
        }

        cleanupVictims();
        return this;
    }

    // 辅助方法：判断目标是否在失败冷却中
    private boolean isInFailedCooldown(EntityID id) {
        Integer end = failedTargets.get(id);
        return end != null && this.agentInfo.getTime() < end;
    }

    private void processCivilianMessage(MessageCivilian mc) {
        EntityID vid = mc.getAgentID();
        if (mc.isHPDefined() && mc.getHP() == 0) return;
        boolean isBuried = mc.isBuriednessDefined() && mc.getBuriedness() > 0;
        boolean hasDamage = mc.isDamageDefined() && mc.getDamage() > 0;
        if (this.agentType == AMBULANCE_TEAM && !isBuried && hasDamage) {
            StandardEntity e = worldInfo.getEntity(vid);
            if (e instanceof Civilian && !loadedVictimsCache.contains(vid))
                knownUnburiedVictims.add(vid);
        } else if (this.agentType == FIRE_BRIGADE && isBuried && !isInFailedCooldown(vid)) {
            if (knownBuriedVictims.add(vid)) {
                int now = agentInfo.getTime();
                targetStartTime.put(vid, now);
                targetLastProgress.put(vid, now);
                targetLastBuriedness.put(vid, mc.getBuriedness());
            }
            if (mc.isPositionDefined()) scanBuildingForVictims(mc.getPosition());
        }
    }

    private void processFireBrigadeMessage(MessageFireBrigade mfb) {
        if (this.agentType != FIRE_BRIGADE) return;
        EntityID vid = mfb.getAgentID();
        if (mfb.isBuriednessDefined() && mfb.getBuriedness() > 0) {
            if (!isInFailedCooldown(vid) && knownBuriedVictims.add(vid)) {
                int now = agentInfo.getTime();
                targetStartTime.put(vid, now);
                targetLastProgress.put(vid, now);
                targetLastBuriedness.put(vid, 0);
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
                    if (!isInFailedCooldown(c.getID()) && knownBuriedVictims.add(c.getID())) {
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
        // 救护车清理
        List<EntityID> toRemove = new ArrayList<>();
        for (EntityID vid : knownUnburiedVictims) {
            Human h = (Human) worldInfo.getEntity(vid);
            if (h == null || h.isHPDefined() && h.getHP() == 0 || h.isDamageDefined() && h.getDamage() == 0
                || !h.isPositionDefined() || (worldInfo.getEntity(h.getPosition()) instanceof Building
                && ((Building)worldInfo.getEntity(h.getPosition())).getStandardURN() == REFUGE)) {
                toRemove.add(vid);
                loadedVictimsCache.add(vid);
            }
        }
        knownUnburiedVictims.removeAll(toRemove);
        // 消防车清理
        toRemove.clear();
        for (EntityID vid : knownBuriedVictims) {
            Human h = (Human) worldInfo.getEntity(vid);
            if (h == null || h.isHPDefined() && h.getHP() == 0
                || h.isBuriednessDefined() && h.getBuriedness() == 0) {
                toRemove.add(vid);
            }
        }
        for (EntityID vid : toRemove) {
            knownBuriedVictims.remove(vid);
            targetStartTime.remove(vid);
            targetLastProgress.remove(vid);
            targetLastBuriedness.remove(vid);
        }
        // 清理 loadedVictimsCache
        loadedVictimsCache.removeIf(vid -> {
            Human h = (Human) worldInfo.getEntity(vid);
            return h == null || h.isHPDefined() && h.getHP() == 0
                || !h.isPositionDefined() || (worldInfo.getEntity(h.getPosition()) instanceof Building
                && ((Building)worldInfo.getEntity(h.getPosition())).getStandardURN() == REFUGE);
        });
    }

    @Override
    public HumanDetector calc() {
        try {
            this.result = null;

            if (this.agentType == AMBULANCE_TEAM) {
                // 救护车逻辑：优先选择已知未装载伤员
                if (!knownUnburiedVictims.isEmpty()) {
                    Set<EntityID> valid = new HashSet<>();
                    for (EntityID vid : knownUnburiedVictims) {
                        Human h = (Human) worldInfo.getEntity(vid);
                        if (h != null && h.isPositionDefined() && h.isDamageDefined() && h.getDamage() > 0) {
                            EntityID pos = h.getPosition();
                            if (pos != null && !(worldInfo.getEntity(pos) instanceof Building && 
                                 ((Building)worldInfo.getEntity(pos)).getStandardURN() == REFUGE)) {
                                valid.add(vid);
                            }
                        }
                    }
                    if (!valid.isEmpty()) {
                        this.result = findNearestVictim(valid);
                        if (this.result != null) return this;
                    }
                }

                // 附近直接发现伤员
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
                        } else if (hasDamage) {
                            EntityID pos = c.getPosition();
                            if (pos != null && worldInfo.getEntity(pos) instanceof Building
                                && ((Building)worldInfo.getEntity(pos)).getStandardURN() == REFUGE) {
                                continue;
                            }
                            this.result = c.getID();
                            return this;
                        }
                    }
                }
                return this;
            }

            if (this.agentType == FIRE_BRIGADE) {
                // 消防车逻辑：优先处理已知掩埋目标（排除冷却中的）
                if (!knownBuriedVictims.isEmpty()) {
                    Set<EntityID> active = new HashSet<>();
                    for (EntityID vid : knownBuriedVictims) {
                        if (isInFailedCooldown(vid)) continue;
                        Human h = (Human) worldInfo.getEntity(vid);
                        if (h != null && h.isBuriednessDefined() && h.getBuriedness() > 0) {
                            active.add(vid);
                        }
                    }
                    Set<EntityID> civilians = new HashSet<>();
                    for (EntityID vid : active) {
                        Human h = (Human) worldInfo.getEntity(vid);
                        if (h instanceof Civilian) civilians.add(vid);
                    }
                    EntityID best = null;
                    if (!civilians.isEmpty()) best = findNearestVictim(civilians);
                    else if (!active.isEmpty()) best = findNearestVictim(active);
                    if (best != null) {
                        this.result = best;
                        if (!targetStartTime.containsKey(best)) {
                            int now = agentInfo.getTime();
                            targetStartTime.put(best, now);
                            targetLastProgress.put(best, now);
                            Human h = (Human) worldInfo.getEntity(best);
                            targetLastBuriedness.put(best, h != null && h.isBuriednessDefined() ? h.getBuriedness() : 0);
                        }
                        if (!reportedVictims.contains(best)) {
                            Human victim = (Human) worldInfo.getEntity(best);
                            if (victim != null) sendReportMessage(victim);
                            reportedVictims.add(best);
                            // 通知警察开路
                            if (victim != null && victim.isPositionDefined() && this.msgManager != null) {
                                EntityID buildingId = victim.getPosition();
                                if (buildingId != null) {
                                    MessageFireBrigade roadRequest = new MessageFireBrigade(
                                        true,
                                        (FireBrigade) this.agentInfo.me(),
                                        MessageFireBrigade.ACTION_RESCUE,
                                        buildingId
                                    );
                                    this.msgManager.addMessage(roadRequest);
                                }
                            }
                        }
                        return this;
                    }
                }

                // 如果没有已知掩埋目标，使用聚类或全局搜索
                if (clustering == null) {
                    this.result = this.calcTargetInWorld();
                } else {
                    this.result = this.calcTargetInCluster(clustering);
                    if (this.result == null) this.result = this.calcTargetInWorld();
                }
                if (this.result != null && !reportedVictims.contains(this.result)) {
                    Human h = (Human) worldInfo.getEntity(this.result);
                    if (h != null) sendReportMessage(h);
                    reportedVictims.add(this.result);
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