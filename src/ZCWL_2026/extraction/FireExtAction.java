package ZCWL_2026.extraction;

import adf.core.agent.action.Action;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.action.common.ActionRest;
import adf.core.agent.action.fire.ActionExtinguish;
import adf.core.agent.action.fire.ActionRefill;
import adf.core.agent.action.ambulance.ActionRescue;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.information.MessageCivilian;
import adf.core.agent.communication.standard.bundle.information.MessageFireBrigade;
import adf.core.agent.communication.standard.bundle.information.MessageRoad;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.extaction.ExtAction;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.config.NoSuchConfigOptionException;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.util.*;

import static rescuecore2.standard.entities.StandardEntityURN.*;

public class FireExtAction extends ExtAction {

    // ==================== 配置参数 ====================
    private int maxExtinguishDistance;
    private int maxExtinguishPower;
    private int thresholdRest;
    private int kernelTime;
    private int refillCompleted;
    private int refillRequest;
    private boolean refillFlag;
    
    private PathPlanning pathPlanning;
    private EntityID target;
    private MessageManager msgManager;
    private Set<EntityID> reportedVictims;
    
    // ========== 防卡死参数 ==========
    private static final int MAX_STUCK_COUNT = 30;
    private static final int MAX_NO_PROGRESS = 20;
    private EntityID lastPosition;
    private int stuckCounter;
    private EntityID lastTarget;
    private int noProgressCounter;

    // ========== 救援完成检测 ==========
    private EntityID previousTarget;
    private boolean wasBuriedLastCheck;

    // ========== 新增：视觉上报道路状态 ==========
    private Set<EntityID> reportedRoads = new HashSet<>();
    // ========== 新增：冷却计时器 ==========
    private Map<EntityID, Integer> roadReportCooldown = new HashMap<>();
    private static final int REPORT_COOLDOWN = 20; // 同一路段至少20步内不重复上报

    public FireExtAction(AgentInfo agentInfo, WorldInfo worldInfo, ScenarioInfo scenarioInfo,
                         ModuleManager moduleManager, DevelopData developData) {
        super(agentInfo, worldInfo, scenarioInfo, moduleManager, developData);
        
        this.target = null;
        this.thresholdRest = developData.getInteger("FireExtAction.rest", 100);
        this.reportedVictims = new HashSet<>();
        this.refillFlag = false;
        
        this.lastPosition = null;
        this.stuckCounter = 0;
        this.lastTarget = null;
        this.noProgressCounter = 0;
        
        this.previousTarget = null;
        this.wasBuriedLastCheck = false;
        
        this.maxExtinguishDistance = scenarioInfo.getFireExtinguishMaxDistance();
        this.maxExtinguishPower = scenarioInfo.getFireExtinguishMaxSum();
        int maxWater = scenarioInfo.getFireTankMaximum();
        this.refillCompleted = (maxWater / 10) * developData.getInteger("FireExtAction.refill.completed", 10);
        this.refillRequest = this.maxExtinguishPower * developData.getInteger("FireExtAction.refill.request", 1);

        switch (scenarioInfo.getMode()) {
            case PRECOMPUTATION_PHASE:
            case PRECOMPUTED:
            case NON_PRECOMPUTE:
                this.pathPlanning = moduleManager.getModule(
                        "FireExtAction.PathPlanning",
                        "ZCWL_2026.module.algorithm.PathPlanning");
                break;
        }
        
        //System.err.println("[消防车动作]");
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
        this.target = target;
        if (target != null && !target.equals(previousTarget)) {
            previousTarget = target;
            wasBuriedLastCheck = isTargetBuried(target);
        }
        return this;
    }

    @Override
public ExtAction calc() {
    try {
        this.result = null;
        FireBrigade agent = (FireBrigade) this.agentInfo.me();
        
        // ========== 新增：视觉上报道路状态 ==========
        reportRoadStatus(agent);
        
        // 防卡死检查
        if (isStuck()) {
            this.target = null;
            return this;
        }
        
        if (hasNoProgress()) {
            this.target = null;
        }

        // 检测救援完成并通知救护车
        checkAndNotifyRescueCompletion();

        if (this.needRest(agent)) {
            this.result = this.calcRefugeAction(agent, this.pathPlanning, this.target);
            if (this.result != null) return this;
        }

        this.refillFlag = this.needRefill(agent, this.refillFlag);
        if (this.refillFlag) {
            this.result = this.calcRefill(agent, this.pathPlanning, this.target);
            if (this.result != null) return this;
        }

        if (this.target == null) return this;
        
        StandardEntity targetEntity = this.worldInfo.getEntity(this.target);
        if (targetEntity == null) return this;
        
        if (targetEntity instanceof Human) {
            this.result = this.calcRescue(agent, this.pathPlanning, this.target);
        } else if (targetEntity instanceof Building) {
            this.result = this.calcExtinguish(agent, this.pathPlanning, this.target);
        } else if (targetEntity instanceof Area) {
            this.result = this.calcMoveToArea(agent, this.pathPlanning, this.target);
        }
    } catch (Exception e) {
        System.err.println("[FireExtAction] calc() 异常: " + e.getMessage());
        e.printStackTrace();
        // 异常时返回休息动作，防止智能体崩溃
        this.result = new ActionRest();
    }
    return this;
}
    
    // ========== 新增：视觉上报道路状态 ==========
    private void reportRoadStatus(FireBrigade agent) {
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
            true,           // 语音
            road,           // 道路
            blockade,       // 障碍物（可为 null）
            !hasBlockade,   // passable
            false           // 不发送坐标
        );
        this.msgManager.addMessage(msg);
        reportedRoads.add(roadId);
        roadReportCooldown.put(roadId, currentTime);  // 新增：记录上报时间
        
        if (hasBlockade) {
            //System.err.println("[消防车] " + agentInfo.getID() + " 视觉上报: 道路 " + roadId + " 有障碍物");
        }
    }
    
    // ==================== 救援完成检测 ====================
    private void checkAndNotifyRescueCompletion() {
        if (target == null || msgManager == null) return;
        
        StandardEntity entity = worldInfo.getEntity(target);
        if (!(entity instanceof Civilian)) return;
        
        Civilian civilian = (Civilian) entity;
        boolean isCurrentlyBuried = civilian.isBuriednessDefined() && civilian.getBuriedness() > 0;
        
        if (wasBuriedLastCheck && !isCurrentlyBuried) {
            MessageCivilian msg = new MessageCivilian(true, civilian);
            msgManager.addMessage(msg);
            //System.err.println("[消防车] 🎉 救援完成！平民 " + target + " 已挖出，发送语音通知救护车");
            
            FireBrigade me = (FireBrigade) agentInfo.me();
            MessageFireBrigade actionMsg = new MessageFireBrigade(
                    true, me, MessageFireBrigade.ACTION_REST, me.getPosition());
            msgManager.addMessage(actionMsg);
        }
        
        wasBuriedLastCheck = isCurrentlyBuried;
        if (!target.equals(previousTarget)) {
            previousTarget = target;
        }
    }
    
    private boolean isTargetBuried(EntityID targetId) {
        if (targetId == null) return false;
        StandardEntity entity = worldInfo.getEntity(targetId);
        if (entity instanceof Civilian) {
            Civilian c = (Civilian) entity;
            return c.isBuriednessDefined() && c.getBuriedness() > 0;
        }
        return false;
    }
    
    // ==================== 防卡死方法 ====================
    private boolean isStuck() {
        EntityID currentPos = this.agentInfo.getPosition();
        if (lastPosition != null && lastPosition.equals(currentPos)) {
            stuckCounter++;
            if (stuckCounter > MAX_STUCK_COUNT) {
                //System.err.println("[消防车] ID:" + this.agentInfo.getID() + 
                //                   " ⚠️ 卡住超过 " + MAX_STUCK_COUNT + " 步，重置目标");
                stuckCounter = 0;
                return true;
            }
        } else {
            stuckCounter = 0;
            lastPosition = currentPos;
        }
        return false;
    }
    
    private boolean hasNoProgress() {
        if (lastTarget != null && lastTarget.equals(this.target)) {
            noProgressCounter++;
            if (noProgressCounter > MAX_NO_PROGRESS) {
                //System.err.println("[消防车] ID:" + this.agentInfo.getID() + 
                //                   " ⚠️ 对目标 " + this.target + " 无进展超过20步，放弃");
                noProgressCounter = 0;
                return true;
            }
        } else {
            noProgressCounter = 0;
            lastTarget = this.target;
        }
        return false;
    }

    // ==================== 灭火相关方法 ====================
    private Action calcExtinguish(FireBrigade agent, PathPlanning pathPlanning, EntityID target) {
        EntityID agentPosition = agent.getPosition();
        StandardEntity positionEntity = Objects.requireNonNull(this.worldInfo.getPosition(agent));
        
        if (positionEntity.getStandardURN() == REFUGE) {
            Action action = this.getMoveAction(pathPlanning, agentPosition, target);
            if (action != null) return action;
        }

        List<StandardEntity> neighbourBuilding = new ArrayList<>();
        StandardEntity entity = this.worldInfo.getEntity(target);
        if (entity instanceof Building) {
            if (this.worldInfo.getDistance(positionEntity, entity) < this.maxExtinguishDistance) {
                neighbourBuilding.add(entity);
            }
        }

        if (!neighbourBuilding.isEmpty()) {
            neighbourBuilding.sort(new DistanceSorter(this.worldInfo, agent));
            Building building = (Building) neighbourBuilding.get(0);
            System.err.println("[消防车] 🔥 灭火: " + building.getID() + 
                               ", 火势: " + building.getFieryness());
            return new ActionExtinguish(building.getID(), this.maxExtinguishPower);
        }
        return this.getMoveAction(pathPlanning, agentPosition, target);
    }

    private Action getMoveAction(PathPlanning pathPlanning, EntityID from, EntityID target) {
        pathPlanning.setFrom(from);
        pathPlanning.setDestination(target);
        List<EntityID> path = pathPlanning.calc().getResult();
        if (path != null && !path.isEmpty()) {
            StandardEntity entity = this.worldInfo.getEntity(path.get(path.size() - 1));
            if (entity instanceof Building && entity.getStandardURN() != REFUGE) {
                path.remove(path.size() - 1);
            }
            return new ActionMove(path);
        }
        return null;
    }

    private boolean needRefill(FireBrigade agent, boolean refillFlag) {
        if (refillFlag) {
            StandardEntityURN positionURN = Objects.requireNonNull(this.worldInfo.getPosition(agent)).getStandardURN();
            return !(positionURN == REFUGE || positionURN == HYDRANT) || agent.getWater() < this.refillCompleted;
        }
        return agent.getWater() <= this.refillRequest;
    }

    private Action calcRefill(FireBrigade agent, PathPlanning pathPlanning, EntityID target) {
        StandardEntityURN positionURN = Objects.requireNonNull(this.worldInfo.getPosition(agent)).getStandardURN();
        if (positionURN == REFUGE) {
            System.err.println("[消防车] 💧 补水");
            return new ActionRefill();
        }
        Action action = this.calcRefugeAction(agent, pathPlanning, target, true);
        if (action != null) return action;
        action = this.calcHydrantAction(agent, pathPlanning, target);
        if (action != null) {
            if (positionURN == HYDRANT && action.getClass().equals(ActionMove.class)) {
                pathPlanning.setFrom(agent.getPosition());
                pathPlanning.setDestination(target);
                double currentDistance = pathPlanning.calc().getDistance();
                List<EntityID> path = ((ActionMove) action).getPath();
                pathPlanning.setFrom(path.get(path.size() - 1));
                pathPlanning.setDestination(target);
                double newHydrantDistance = pathPlanning.calc().getDistance();
                if (currentDistance <= newHydrantDistance) {
                    return new ActionRefill();
                }
            }
            return action;
        }
        return null;
    }

    private Action calcRefugeAction(Human human, PathPlanning pathPlanning, EntityID target, boolean isRefill) {
        return this.calcSupplyAction(human, pathPlanning, this.worldInfo.getEntityIDsOfType(REFUGE), target, isRefill);
    }

    private Action calcHydrantAction(Human human, PathPlanning pathPlanning, EntityID target) {
        Collection<EntityID> hydrants = this.worldInfo.getEntityIDsOfType(HYDRANT);
        hydrants.remove(human.getPosition());
        return this.calcSupplyAction(human, pathPlanning, hydrants, target, true);
    }

    private Action calcSupplyAction(Human human, PathPlanning pathPlanning, Collection<EntityID> supplyPositions,
                                     EntityID target, boolean isRefill) {
        EntityID position = human.getPosition();
        int size = supplyPositions.size();
        if (supplyPositions.contains(position)) {
            return isRefill ? new ActionRefill() : new ActionRest();
        }
        List<EntityID> firstResult = null;
        while (!supplyPositions.isEmpty()) {
            pathPlanning.setFrom(position);
            pathPlanning.setDestination(supplyPositions);
            List<EntityID> path = pathPlanning.calc().getResult();
            if (path != null && !path.isEmpty()) {
                if (firstResult == null) {
                    firstResult = new ArrayList<>(path);
                    if (target == null) break;
                }
                EntityID supplyPositionID = path.get(path.size() - 1);
                pathPlanning.setFrom(supplyPositionID);
                pathPlanning.setDestination(target);
                List<EntityID> fromRefugeToTarget = pathPlanning.calc().getResult();
                if (fromRefugeToTarget != null && !fromRefugeToTarget.isEmpty()) {
                    return new ActionMove(path);
                }
                supplyPositions.remove(supplyPositionID);
                if (size == supplyPositions.size()) break;
                size = supplyPositions.size();
            } else {
                break;
            }
        }
        return firstResult != null ? new ActionMove(firstResult) : null;
    }

    // ==================== 救援相关方法 ====================
    private boolean isValidVictim(Human victim) {
        if (victim == null) return false;
        if (!victim.isPositionDefined()) return false;
        EntityID pos = victim.getPosition();
        if (pos == null) return false;
        StandardEntity posEntity = this.worldInfo.getEntity(pos);
        return posEntity != null;
    }
    
    private boolean isInRefuge(Human human) {
        if (!human.isPositionDefined()) return false;
        EntityID pos = human.getPosition();
        if (pos == null) return false;
        StandardEntity posEntity = this.worldInfo.getEntity(pos);
        return posEntity != null && posEntity.getStandardURN() == REFUGE;
    }
    
    private Action calcRescue(FireBrigade agent, PathPlanning pathPlanning, EntityID targetID) {
        Human targetHuman = (Human) this.worldInfo.getEntity(targetID);
        if (targetHuman == null) {
            System.err.println("[消防车] ⚠️ 目标 " + targetID + " 不存在");
            this.target = null;
            return null;
        }
        
        boolean isBuried = targetHuman.isBuriednessDefined() && targetHuman.getBuriedness() > 0;
        boolean isUnburied = targetHuman.isBuriednessDefined() && targetHuman.getBuriedness() == 0;
        
        if (isUnburied) {
            System.err.println("[消防车] ✅ 目标 " + targetID + " 已被挖出，无需救援");
            if (targetHuman.isDamageDefined() && targetHuman.getDamage() > 0 && !reportedVictims.contains(targetID)) {
                if (isValidVictim(targetHuman)) {
                    reportVictimToAmbulance(targetHuman);
                }
            }
            this.target = null;
            return null;
        }
        
        if (!isBuried) {
            System.err.println("[消防车] ⚠️ 目标 " + targetID + " 埋压度未定义或为0，放弃");
            this.target = null;
            return null;
        }
        
        if (!isValidVictim(targetHuman)) {
            System.err.println("[消防车] ⚠️ 目标 " + targetID + " 位置无效，放弃任务");
            this.target = null;
            return null;
        }
        
        EntityID agentPosition = agent.getPosition();
        EntityID targetPosition = targetHuman.getPosition();
        
        if (targetPosition == null) {
            System.err.println("[消防车] ⚠️ 目标 " + targetID + " 位置为 null，放弃任务");
            this.target = null;
            return null;
        }
        
        if (isInRefuge(targetHuman)) {
            System.err.println("[消防车] 🏥 目标 " + targetID + " 已在避难所，放弃任务");
            this.target = null;
            return null;
        }
        
        boolean isDead = (targetHuman.isHPDefined() && targetHuman.getHP() == 0);
        if (isDead) {
            this.target = null;
            return null;
        }
        
        if (agentPosition.getValue() == targetPosition.getValue()) {
            if (targetHuman.isBuriednessDefined() && targetHuman.getBuriedness() > 0) {
                /*System.err.println("[消防车] 🆘 救援被困单位: " + targetHuman.getID() + 
                                   " 类型=" + targetHuman.getStandardURN() +
                                   " 埋压度=" + targetHuman.getBuriedness());*/
                return new ActionRescue(targetHuman);
            }
        }
        
        List<EntityID> path = pathPlanning.getResult(agentPosition, targetPosition);
        if (path != null && !path.isEmpty()) {
            int distance = worldInfo.getDistance(agentPosition, targetPosition);
            //System.err.println("[消防车] 📍 移动到被困单位位置: " + targetPosition + " 距离=" + distance);
            return new ActionMove(path);
        }
        
        System.err.println("[消防车] ⚠️ 无法计算到目标 " + targetID + " 的路径");
        return null;
    }
    
    private void reportVictimToAmbulance(Human victim) {
        if (this.msgManager == null) return;
        if (reportedVictims.contains(victim.getID())) return;
        if (!victim.isPositionDefined()) return;
        EntityID pos = victim.getPosition();
        if (pos == null) return;
        if (victim.isDamageDefined() && victim.getDamage() == 0) return;
        
        if (victim instanceof Civilian) {
            MessageCivilian msg = new MessageCivilian(false, (Civilian) victim);
            this.msgManager.addMessage(msg);
            reportedVictims.add(victim.getID());
            System.err.println("[消防车] 📢 已报告已挖出伤员: " + victim.getID());
        }
    }
    
    private Action calcMoveToArea(FireBrigade agent, PathPlanning pathPlanning, EntityID target) {
        EntityID agentPosition = agent.getPosition();
        List<EntityID> path = pathPlanning.getResult(agentPosition, target);
        if (path != null && !path.isEmpty()) {
            return new ActionMove(path);
        }
        return null;
    }

    // ==================== 通用辅助方法 ====================
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

    private Action calcRefugeAction(Human human, PathPlanning pathPlanning, EntityID target) {
        EntityID position = human.getPosition();
        Collection<EntityID> refuges = this.worldInfo.getEntityIDsOfType(REFUGE);
        if (refuges.contains(position)) {
            return new ActionRest();
        }
        List<EntityID> firstResult = null;
        while (!refuges.isEmpty()) {
            pathPlanning.setFrom(position);
            pathPlanning.setDestination(refuges);
            List<EntityID> path = pathPlanning.calc().getResult();
            if (path != null && !path.isEmpty()) {
                if (firstResult == null) {
                    firstResult = new ArrayList<>(path);
                    if (target == null) break;
                }
                EntityID refugeID = path.get(path.size() - 1);
                pathPlanning.setFrom(refugeID);
                pathPlanning.setDestination(target);
                List<EntityID> fromRefugeToTarget = pathPlanning.calc().getResult();
                if (fromRefugeToTarget != null && !fromRefugeToTarget.isEmpty()) {
                    return new ActionMove(path);
                }
                refuges.remove(refugeID);
            } else {
                break;
            }
        }
        return firstResult != null ? new ActionMove(firstResult) : null;
    }

    private class DistanceSorter implements Comparator<StandardEntity> {
        private StandardEntity reference;
        private WorldInfo worldInfo;
        DistanceSorter(WorldInfo wi, StandardEntity reference) {
            this.reference = reference;
            this.worldInfo = wi;
        }
        public int compare(StandardEntity a, StandardEntity b) {
            int d1 = this.worldInfo.getDistance(this.reference, a);
            int d2 = this.worldInfo.getDistance(this.reference, b);
            return d1 - d2;
        }
    }

    @Override
    public ExtAction precompute(PrecomputeData pd) { return this; }
    @Override
    public ExtAction resume(PrecomputeData pd) { return this; }
    @Override
    public ExtAction preparate() { return this; }
}