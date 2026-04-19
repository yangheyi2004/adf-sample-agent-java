package ZCWL_2026.extraction;

import adf.core.agent.action.Action;
import adf.core.agent.action.ambulance.ActionLoad;
import adf.core.agent.action.ambulance.ActionRescue;
import adf.core.agent.action.ambulance.ActionUnload;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.action.common.ActionRest;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.centralized.MessageReport;
import adf.core.agent.communication.standard.bundle.information.*;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.extaction.ExtAction;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.config.NoSuchConfigOptionException;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.util.*;
import static rescuecore2.standard.entities.StandardEntityURN.*;

public class ActionTransport extends ExtAction {

    private PathPlanning pathPlanning;
    private int thresholdRest;
    private int kernelTime;
    private EntityID target;
    private MessageManager msgManager;
    
    private static final int MAX_LOAD_DISTANCE = 100;
    private static final int MOVE_CLOSE_DISTANCE = 80;
    private static final int MAX_STUCK_COUNT = 30;
    private static final int MAX_NO_PROGRESS = 20;
    
    private EntityID lastPosition;
    private int stuckCounter;
    private EntityID lastTarget;
    private int noProgressCounter;
    
    private Set<EntityID> processedVictims;
    private Set<EntityID> invalidBuildings;

    // ========== 新增：视觉上报道路状态 ==========
    private Set<EntityID> reportedRoads = new HashSet<>();
    // ========== 新增：冷却计时器 ==========
    private Map<EntityID, Integer> roadReportCooldown = new HashMap<>();
    private static final int REPORT_COOLDOWN = 20;// 同一路段至少20步内不重复上报

    public ActionTransport(AgentInfo agentInfo, WorldInfo worldInfo, ScenarioInfo scenarioInfo,
                           ModuleManager moduleManager, DevelopData developData) {
        super(agentInfo, worldInfo, scenarioInfo, moduleManager, developData);
        this.target = null;
        this.thresholdRest = developData.getInteger("ActionTransport.rest", 100);
        this.lastPosition = null;
        this.stuckCounter = 0;
        this.lastTarget = null;
        this.noProgressCounter = 0;
        this.processedVictims = new HashSet<>();
        this.invalidBuildings = new HashSet<>();

        switch (scenarioInfo.getMode()) {
            case PRECOMPUTATION_PHASE:
            case PRECOMPUTED:
            case NON_PRECOMPUTE:
                this.pathPlanning = moduleManager.getModule(
                        "ActionTransport.PathPlanning",
                        "ZCWL_2026.module.algorithm.PathPlanning");
                break;
        }
    }

    @Override
    public ExtAction updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() >= 2) return this;
        this.pathPlanning.updateInfo(messageManager);
        this.msgManager = messageManager;
        return this;
    }

    @Override
    public ExtAction setTarget(EntityID target) {
        this.target = null;
        if (target != null) {
            StandardEntity entity = this.worldInfo.getEntity(target);
            if (entity instanceof Human || entity instanceof Area) {
                this.target = target;
            }
        }
        return this;
    }

    @Override
public ExtAction calc() {
    try {
        this.result = null;
        AmbulanceTeam agent = (AmbulanceTeam) this.agentInfo.me();
        EntityID agentID = this.agentInfo.getID();
        EntityID currentPos = this.agentInfo.getPosition();
        
        // ========== 新增：视觉上报道路状态 ==========
        reportRoadStatus(agent);
        
        if (lastPosition != null && lastPosition.equals(currentPos)) {
            stuckCounter++;
            if (stuckCounter > MAX_STUCK_COUNT) {
                //System.err.println("[救护车] ID:" + agentID + " ⚠️ 卡住超过30步，重置状态");
                this.target = null;
                stuckCounter = 0;
            }
        } else {
            stuckCounter = 0;
            lastPosition = currentPos;
        }
        
        if (lastTarget != null && lastTarget.equals(this.target)) {
            noProgressCounter++;
            if (noProgressCounter > MAX_NO_PROGRESS) {
                //System.err.println("[救护车] ID:" + agentID + " ⚠️ 对目标 " + this.target + " 无进展，放弃");
                this.target = null;
                noProgressCounter = 0;
            }
        } else {
            noProgressCounter = 0;
            lastTarget = this.target;
        }

        Human transportHuman = this.agentInfo.someoneOnBoard();
        if (transportHuman != null) {
            //System.err.println("[救护车] 🚑 车上有伤员 ID:" + transportHuman.getID());
            this.result = this.calcUnloadToRefuge(agent);
            if (this.result != null) return this;
        }

        if (this.needRest(agent)) {
            this.result = this.calcRefugeAction(agent, this.pathPlanning, null, false);
            if (this.result != null) return this;
        }
        
        if (this.target != null) {
            this.result = this.calcLoadOrRescue(agent, this.pathPlanning, this.target);
        }
    } catch (Exception e) {
        System.err.println("[ActionTransport] calc() 异常: " + e.getMessage());
        e.printStackTrace();
        // 异常时返回休息动作，防止智能体崩溃
        this.result = new ActionRest();
    }
    return this;
}

    // ========== 新增：视觉上报道路状态 ==========
    private void reportRoadStatus(AmbulanceTeam agent) {
        if (this.msgManager == null) return;
        EntityID currentPos = agent.getPosition();
        if (currentPos == null) return;
        
        StandardEntity posEntity = this.worldInfo.getEntity(currentPos);
        if (posEntity != null && posEntity instanceof Road) {
            reportSingleRoad((Road) posEntity);
        }
        
        Collection<StandardEntity> visibleEntities = this.worldInfo.getObjectsInRange(currentPos, 25000);
        for (StandardEntity e : visibleEntities) {
            if (e != null && e instanceof Road) {
                reportSingleRoad((Road) e);
            }
        }
    }
    
    private void reportSingleRoad(Road road) {
        EntityID roadId = road.getID();
        
        // ========== 新增：冷却检查 ==========
        int currentTime = agentInfo.getTime();
        Integer lastReport = roadReportCooldown.get(roadId);
        if (lastReport != null && currentTime - lastReport < REPORT_COOLDOWN) {
            return; // 冷却中，不上报
        }
        
        if (reportedRoads.contains(roadId)) return;
        if (!road.isBlockadesDefined()) return;
        
        boolean hasBlockade = !road.getBlockades().isEmpty();
        Blockade blockade = null;
        if (hasBlockade) {
            EntityID blockadeId = road.getBlockades().get(0);
            StandardEntity be = this.worldInfo.getEntity(blockadeId);
            if (be instanceof Blockade) {
                blockade = (Blockade) be;
            }
        }
        
        MessageRoad msg = new MessageRoad(
            true,
            road,
            blockade,
            !hasBlockade,
            false
        );
        this.msgManager.addMessage(msg);
        reportedRoads.add(roadId);
        roadReportCooldown.put(roadId, currentTime);  // 新增：记录上报时间
        
        if (hasBlockade) {
            //System.err.println("[救护车] " + agentInfo.getID() + " 视觉上报: 道路 " + roadId + " 有障碍物");
        }
    }

    private Action calcUnloadToRefuge(AmbulanceTeam agent) {
        EntityID agentPos = agent.getPosition();
        Collection<EntityID> refuges = this.worldInfo.getEntityIDsOfType(REFUGE);
        
        if (refuges.contains(agentPos)) {
            //System.err.println("[救护车] 🏥 到达避难所，卸载伤员");
            
            Human passenger = this.agentInfo.someoneOnBoard();
            if (passenger != null && passenger instanceof Civilian) {
                if (passenger.isPositionDefined() && passenger.getPosition() != null) {
                    MessageCivilian msg = new MessageCivilian(false, (Civilian) passenger);
                    this.msgManager.addMessage(msg);
                }
            }
            return new ActionUnload();
        }
        
        this.pathPlanning.setFrom(agentPos);
        this.pathPlanning.setDestination(refuges);
        List<EntityID> path = this.pathPlanning.calc().getResult();
        if (path != null && !path.isEmpty()) {
            return new ActionMove(path);
        }
        
        return null;
    }

    private EntityID findNearestRefuge(EntityID position) {
        Collection<EntityID> refuges = this.worldInfo.getEntityIDsOfType(REFUGE);
        EntityID nearest = null;
        double minDist = Double.MAX_VALUE;
        for (EntityID refugeId : refuges) {
            double dist = this.worldInfo.getDistance(position, refugeId);
            if (dist < minDist) {
                minDist = dist;
                nearest = refugeId;
            }
        }
        return nearest;
    }

    private boolean isValidBuilding(EntityID buildingId) {
        if (buildingId == null) return false;
        if (invalidBuildings.contains(buildingId)) return false;
        
        StandardEntity entity = this.worldInfo.getEntity(buildingId);
        if (!(entity instanceof Building)) return true;
        
        Building building = (Building) entity;
        if (!building.isXDefined() || !building.isYDefined()) {
            invalidBuildings.add(buildingId);
            return false;
        }
        
        int x = building.getX();
        int y = building.getY();
        if (Math.abs(x) <= 10 && Math.abs(y) <= 10) {
            invalidBuildings.add(buildingId);
            return false;
        }
        
        return true;
    }
    
    private boolean isValidPositionEntity(EntityID positionId) {
        if (positionId == null) return false;
        StandardEntity entity = this.worldInfo.getEntity(positionId);
        if (entity == null) return false;
        if (entity instanceof Building) return isValidBuilding(positionId);
        if (entity instanceof Road) {
            Road road = (Road) entity;
            return road.isXDefined() && road.isYDefined();
        }
        return true;
    }
    
    private boolean isValidAndReachableVictim(EntityID victimId) {
        Human h = (Human) this.worldInfo.getEntity(victimId);
        if (h == null) return false;
        if (!h.isPositionDefined()) return false;
        EntityID pos = h.getPosition();
        if (pos == null) return false;
        return isValidPositionEntity(pos);
    }

    private boolean isVictimInRefuge(EntityID victimId) {
        Human h = (Human) this.worldInfo.getEntity(victimId);
        if (h == null) return false;
        if (!h.isPositionDefined()) return false;
        EntityID pos = h.getPosition();
        if (pos == null) return false;
        StandardEntity posEntity = this.worldInfo.getEntity(pos);
        return posEntity != null && posEntity.getStandardURN() == REFUGE;
    }
    
    private boolean isVictimAlreadyLoaded(EntityID victimId) {
        if (processedVictims.contains(victimId)) return true;
        Human h = (Human) this.worldInfo.getEntity(victimId);
        if (h != null && !h.isPositionDefined()) {
            processedVictims.add(victimId);
            return true;
        }
        return false;
    }
    
    private boolean isTargetSelf(EntityID targetId) {
        EntityID agentID = this.agentInfo.getID();
        if (targetId.equals(agentID)) {
            System.err.println("[救护车] ❌ 严重错误！试图装载自己！ID:" + agentID);
            return true;
        }
        return false;
    }

    private Action calcLoadOrRescue(AmbulanceTeam agent, PathPlanning pathPlanning, EntityID targetID) {
        if (isTargetSelf(targetID)) {
            this.target = null;
            return null;
        }
        
        StandardEntity targetEntity = this.worldInfo.getEntity(targetID);
        if (targetEntity == null) {
            //System.err.println("[救护车] ⚠️ 目标 " + targetID + " 不存在");
            this.target = null;
            return null;
        }
        
        EntityID agentPosition = agent.getPosition();
        
        if (targetEntity instanceof Human) {
            Human human = (Human) targetEntity;
            
            if (human instanceof AmbulanceTeam) {
                //System.err.println("[救护车] ❌ 试图装载其他救护车，拒绝！");
                this.target = null;
                return null;
            }
            
            if (isVictimAlreadyLoaded(targetID)) {
                //System.err.println("[救护车] ⚠️ 目标 " + targetID + " 已被装载");
                this.target = null;
                return null;
            }
            
            if (!isValidAndReachableVictim(targetID)) {
                //System.err.println("[救护车] ⚠️ 目标 " + targetID + " 位置无效");
                this.target = null;
                return null;
            }
            
            if (isVictimInRefuge(targetID)) {
                //System.err.println("[救护车] 🏥 平民 " + targetID + " 已在避难所");
                this.target = null;
                return null;
            }
            
            EntityID targetPosition = human.getPosition();
            
            if (human.isHPDefined() && human.getHP() == 0) {
                //System.err.println("[救护车] ❌ 目标 " + human.getID() + " 已死亡");
                this.target = null;
                return null;
            }
            
            boolean isBuried = human.isBuriednessDefined() && human.getBuriedness() > 0;
            if (isBuried) {
                //System.err.println("[救护车] ⏳ 目标 " + human.getID() + " 仍被掩埋");
                return null;
            }
            
            boolean hasDamage = human.isDamageDefined() && human.getDamage() > 0;
            if (!hasDamage) {
                //System.err.println("[救护车] ⚠️ 目标 " + human.getID() + " 未受伤");
                this.target = null;
                return null;
            }
            
            double distance = this.worldInfo.getDistance(agentPosition, targetPosition);
            
            if (distance <= MOVE_CLOSE_DISTANCE) {
                Human finalCheck = (Human) this.worldInfo.getEntity(targetID);
                if (finalCheck == null || !finalCheck.isPositionDefined()) {
                    this.target = null;
                    return null;
                }
                
                if (isVictimAlreadyLoaded(targetID)) {
                    this.target = null;
                    return null;
                }
                
                if (msgManager != null && targetEntity instanceof Civilian) {
                    MessageCivilian loadedMsg = new MessageCivilian(true, (Civilian) targetEntity);
                    msgManager.addMessage(loadedMsg);
                    //System.err.println("[救护车] 📢 发送装载通知: 平民 " + targetID + " 已被装载");
                }
                
                //System.err.println("╔══════════════════════════════════════════════════════════════╗");
                //System.err.println("║  [救护车] 📦 装载平民: " + targetID);
                //System.err.println("║  距离: " + String.format("%.1f", distance));
                //System.err.println("╚══════════════════════════════════════════════════════════════╝");
                
                processedVictims.add(targetID);
                
                if (this.msgManager != null) {
                    MessageReport report = new MessageReport(true, true, false, this.agentInfo.getID());
                    this.msgManager.addMessage(report);
                }
                
                EntityID nearestRefuge = findNearestRefuge(agentPosition);
                if (nearestRefuge != null) {
                    this.target = nearestRefuge;
                } else {
                    this.target = null;
                }
                return new ActionLoad(targetID);
            }
            
            //System.err.println("[救护车] 📍 移动到目标位置: " + targetPosition + 
            //                  " 距离=" + String.format("%.1f", distance));
            List<EntityID> path = pathPlanning.getResult(agentPosition, targetPosition);
            if (path != null && !path.isEmpty()) {
                return new ActionMove(path);
            }
            return null;
        }
        
        if (targetEntity.getStandardURN() == BLOCKADE) {
            Blockade blockade = (Blockade) targetEntity;
            if (blockade.isPositionDefined()) {
                targetEntity = this.worldInfo.getEntity(blockade.getPosition());
            }
        }
        
        if (targetEntity instanceof Area) {
            if (targetEntity instanceof Building && !isValidBuilding(targetEntity.getID())) {
                this.target = null;
                return null;
            }
            List<EntityID> path = pathPlanning.getResult(agentPosition, targetEntity.getID());
            if (path != null && !path.isEmpty()) {
                return new ActionMove(path);
            }
        }
        return null;
    }

    private boolean needRest(Human agent) {
        int hp = agent.getHP();
        int damage = agent.getDamage();
        if (hp == 0 || damage == 0) return false;
        int activeTime = (hp / damage) + ((hp % damage) != 0 ? 1 : 0);
        if (this.kernelTime == -1) {
            try {
                this.kernelTime = this.scenarioInfo.getKernelTimesteps();
            } catch (NoSuchConfigOptionException e) {
                this.kernelTime = -1;
            }
        }
        return damage >= this.thresholdRest || (activeTime + this.agentInfo.getTime()) < this.kernelTime;
    }

    private Action calcRefugeAction(Human human, PathPlanning pathPlanning, Collection<EntityID> targets, boolean isUnload) {
        EntityID position = human.getPosition();
        Collection<EntityID> refuges = this.worldInfo.getEntityIDsOfType(REFUGE);
        int size = refuges.size();
        if (refuges.contains(position)) {
            return isUnload ? new ActionUnload() : new ActionRest();
        }
        List<EntityID> firstResult = null;
        while (refuges.size() > 0) {
            pathPlanning.setFrom(position);
            pathPlanning.setDestination(refuges);
            List<EntityID> path = pathPlanning.calc().getResult();
            if (path != null && path.size() > 0) {
                if (firstResult == null) {
                    firstResult = new ArrayList<>(path);
                    if (targets == null || targets.isEmpty()) break;
                }
                EntityID refugeID = path.get(path.size() - 1);
                pathPlanning.setFrom(refugeID);
                pathPlanning.setDestination(targets);
                List<EntityID> fromRefugeToTarget = pathPlanning.calc().getResult();
                if (fromRefugeToTarget != null && fromRefugeToTarget.size() > 0) {
                    return new ActionMove(path);
                }
                refuges.remove(refugeID);
                if (size == refuges.size()) break;
                size = refuges.size();
            } else {
                break;
            }
        }
        return firstResult != null ? new ActionMove(firstResult) : null;
    }

    @Override
    public ExtAction precompute(PrecomputeData precomputeData) { return this; }
    @Override
    public ExtAction resume(PrecomputeData precomputeData) { return this; }
    @Override
    public ExtAction preparate() { return this; }
}