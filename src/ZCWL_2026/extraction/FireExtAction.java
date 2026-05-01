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
    
    private static final int MAX_STUCK_COUNT = 30;
    private static final int MAX_NO_PROGRESS = 20;
    private EntityID lastPosition;
    private int stuckCounter;
    private EntityID lastTarget;
    private int noProgressCounter;

    private EntityID previousTarget;
    private boolean wasBuriedLastCheck;

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
            
            if (isStuck()) {
                this.target = null;
                return this;
            }
            
            if (hasNoProgress()) {
                this.target = null;
            }

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
            this.result = new ActionRest();
        }
        return this;
    }
    
    private void checkAndNotifyRescueCompletion() {
    if (target == null || msgManager == null) return;
    
    StandardEntity entity = worldInfo.getEntity(target);
    if (!(entity instanceof Civilian)) return;
    
    Civilian civilian = (Civilian) entity;
    boolean isCurrentlyBuried = civilian.isBuriednessDefined() && civilian.getBuriedness() > 0;
    
    // 检测掩埋状态从 true 变为 false 的瞬间（即救出完成）
    if (wasBuriedLastCheck && !isCurrentlyBuried) {
        // 1. 发送语音消息通知救护车（所有救护车都能收到）
        MessageCivilian msg = new MessageCivilian(true, civilian);
        msgManager.addMessage(msg);
        System.err.println("[FireExtAction] 消防车 " + agentInfo.getID() +
                " 救出平民 " + civilian.getID() + "，已发送语音消息通知救护车");

        // 2. 发送消防车自身的状态消息（告知正在休息，避免被误判为忙碌）
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
    
    private boolean isStuck() {
        EntityID currentPos = this.agentInfo.getPosition();
        if (lastPosition != null && lastPosition.equals(currentPos)) {
            stuckCounter++;
            if (stuckCounter > MAX_STUCK_COUNT) {
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
                noProgressCounter = 0;
                return true;
            }
        } else {
            noProgressCounter = 0;
            lastTarget = this.target;
        }
        return false;
    }

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
            this.target = null;
            return null;
        }
        
        boolean isBuried = targetHuman.isBuriednessDefined() && targetHuman.getBuriedness() > 0;
        boolean isUnburied = targetHuman.isBuriednessDefined() && targetHuman.getBuriedness() == 0;
        
        if (isUnburied) {
            if (targetHuman.isDamageDefined() && targetHuman.getDamage() > 0 && !reportedVictims.contains(targetID)) {
                if (isValidVictim(targetHuman)) {
                    reportVictimToAmbulance(targetHuman);
                }
            }
            this.target = null;
            return null;
        }
        
        if (!isBuried) {
            this.target = null;
            return null;
        }
        
        if (!isValidVictim(targetHuman)) {
            this.target = null;
            return null;
        }
        
        EntityID agentPosition = agent.getPosition();
        EntityID targetPosition = targetHuman.getPosition();
        
        if (targetPosition == null) {
            this.target = null;
            return null;
        }
        
        if (isInRefuge(targetHuman)) {
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
                return new ActionRescue(targetHuman);
            }
        }
        
        List<EntityID> path = pathPlanning.getResult(agentPosition, targetPosition);
        if (path != null && !path.isEmpty()) {
            return new ActionMove(path);
        }
        
        return null;
    }
    
    private void reportVictimToAmbulance(Human victim) {
    if (this.msgManager == null) return;
    if (reportedVictims.contains(victim.getID())) return; // 防止重复发送
    if (!victim.isPositionDefined()) return;
    EntityID pos = victim.getPosition();
    if (pos == null) return;
    if (victim.isDamageDefined() && victim.getDamage() == 0) return; // 无伤害无需装载

    if (victim instanceof Civilian) {
        // 发送无线消息（非语音）告知救护车有需要装载的平民
        MessageCivilian msg = new MessageCivilian(false, (Civilian) victim);
        this.msgManager.addMessage(msg);
        reportedVictims.add(victim.getID());
        System.err.println("[FireExtAction] 消防车 " + agentInfo.getID() +
                " 发送无线消息通知救护车：平民 " + victim.getID() + " 待装载");
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