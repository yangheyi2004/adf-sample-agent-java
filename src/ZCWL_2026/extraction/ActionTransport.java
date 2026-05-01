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
    
    private static final int MAX_STUCK_COUNT = 30;
    private static final int MAX_NO_PROGRESS = 20;
    
    private EntityID lastPosition;
    private int stuckCounter;
    private EntityID lastTarget;
    private int noProgressCounter;
    
    private Set<EntityID> processedVictims;
    private Set<EntityID> invalidBuildings;

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
            
            // 防卡死检测
            if (lastPosition != null && lastPosition.equals(currentPos)) {
                stuckCounter++;
                if (stuckCounter > MAX_STUCK_COUNT) {
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
                    this.target = null;
                    noProgressCounter = 0;
                }
            } else {
                noProgressCounter = 0;
                lastTarget = this.target;
            }

            // 1. 如果车上有伤员，优先卸载到避难所
            Human transportHuman = this.agentInfo.someoneOnBoard();
            if (transportHuman != null) {
                this.result = this.calcUnloadToRefuge(agent);
                if (this.result != null) return this;
            }

            // 2. 休息判断
            if (this.needRest(agent)) {
                this.result = this.calcRefugeAction(agent, this.pathPlanning, null, false);
                if (this.result != null) return this;
            }

            // 3. 如果有目标，尝试装载或救援
            if (this.target != null) {
                this.result = this.calcLoadOrRescue(agent, this.pathPlanning, this.target);
            }
        } catch (Exception e) {
            System.err.println("[ActionTransport] calc() 异常: " + e.getMessage());
            e.printStackTrace();
            this.result = new ActionRest();
        }
        return this;
    }

    private Action calcUnloadToRefuge(AmbulanceTeam agent) {
        EntityID agentPos = agent.getPosition();
        Collection<EntityID> refuges = this.worldInfo.getEntityIDsOfType(REFUGE);
        
        if (refuges.contains(agentPos)) {
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

    private Action calcLoadOrRescue(AmbulanceTeam agent, PathPlanning pathPlanning, EntityID targetID) {
        if (targetID.equals(this.agentInfo.getID())) {
            this.target = null;
            return null;
        }
        
        StandardEntity targetEntity = this.worldInfo.getEntity(targetID);
        if (targetEntity == null) {
            this.target = null;
            return null;
        }
        
        EntityID agentPosition = agent.getPosition();
        
        if (targetEntity instanceof Human) {
            Human human = (Human) targetEntity;
            
            if (human instanceof AmbulanceTeam) {
                this.target = null;
                return null;
            }
            
            if (processedVictims.contains(targetID)) {
                this.target = null;
                return null;
            }
            
            if (!human.isPositionDefined()) {
                this.target = null;
                return null;
            }
            
            EntityID targetPosition = human.getPosition();
            if (targetPosition == null) {
                this.target = null;
                return null;
            }
            
            if (isInRefuge(human)) {
                this.target = null;
                return null;
            }
            
            if (human.isHPDefined() && human.getHP() == 0) {
                this.target = null;
                return null;
            }
            
            // ★ 不再检查伤害值，直接执行装载
            double distance = this.worldInfo.getDistance(agentPosition, targetPosition);
            if (distance <= 100) {
                // 已经在附近，执行装载
                if (msgManager != null && targetEntity instanceof Civilian) {
                    MessageCivilian loadedMsg = new MessageCivilian(true, (Civilian) targetEntity);
                    msgManager.addMessage(loadedMsg);
                }
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
            
            List<EntityID> path = pathPlanning.getResult(agentPosition, targetPosition);
            if (path != null && !path.isEmpty()) {
                return new ActionMove(path);
            }
            return null;
        }
        
        // 如果目标为区域，直接移动过去
        if (targetEntity instanceof Area) {
            List<EntityID> path = pathPlanning.getResult(agentPosition, targetEntity.getID());
            if (path != null && !path.isEmpty()) {
                return new ActionMove(path);
            }
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

    private boolean isInRefuge(Human human) {
        if (!human.isPositionDefined()) return false;
        EntityID pos = human.getPosition();
        if (pos == null) return false;
        StandardEntity posEntity = this.worldInfo.getEntity(pos);
        return posEntity != null && posEntity.getStandardURN() == REFUGE;
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