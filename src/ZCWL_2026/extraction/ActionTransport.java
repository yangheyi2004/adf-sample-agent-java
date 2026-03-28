package ZCWL_2026.extraction;

import adf.core.agent.action.Action;
import adf.core.agent.action.ambulance.ActionLoad;
import adf.core.agent.action.ambulance.ActionRescue;
import adf.core.agent.action.ambulance.ActionUnload;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.action.common.ActionRest;
import adf.core.agent.communication.MessageManager;
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

    public ActionTransport(AgentInfo agentInfo, WorldInfo worldInfo, ScenarioInfo scenarioInfo,
                           ModuleManager moduleManager, DevelopData developData) {
        super(agentInfo, worldInfo, scenarioInfo, moduleManager, developData);
        this.target = null;
        this.thresholdRest = developData.getInteger("ActionTransport.rest", 100);

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
        this.result = null;
        AmbulanceTeam agent = (AmbulanceTeam) this.agentInfo.me();
        Human transportHuman = this.agentInfo.someoneOnBoard();

        if (transportHuman != null) {
            System.err.println("[救护车] 🚑 车上有伤员 ID:" + transportHuman.getID() + "，准备运输到避难所");
            this.result = this.calcUnloadToRefuge(agent);
            if (this.result != null) return this;
        }

        if (this.needRest(agent)) {
            this.result = this.calcRefugeAction(agent, this.pathPlanning, null, false);
            if (this.result != null) return this;
        }
        
        if (this.target != null) {
            this.result = this.calcRescue(agent, this.pathPlanning, this.target);
        }
        return this;
    }

    private Action calcUnloadToRefuge(AmbulanceTeam agent) {
        EntityID agentPos = agent.getPosition();
        Collection<EntityID> refuges = this.worldInfo.getEntityIDsOfType(REFUGE);
        
        if (refuges.contains(agentPos)) {
            System.err.println("[救护车] 🏥 到达避难所，卸载伤员");
            return new ActionUnload();
        }
        
        this.pathPlanning.setFrom(agentPos);
        this.pathPlanning.setDestination(refuges);
        List<EntityID> path = this.pathPlanning.calc().getResult();
        if (path != null && !path.isEmpty()) {
            System.err.println("[救护车] 📍 前往避难所运输伤员: " + path.get(path.size() - 1));
            return new ActionMove(path);
        }
        
        System.err.println("[救护车] ⚠️ 无法找到通往避难所的路径");
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

    private Action calcRescue(AmbulanceTeam agent, PathPlanning pathPlanning, EntityID targetID) {
        StandardEntity targetEntity = this.worldInfo.getEntity(targetID);
        if (targetEntity == null) return null;
        
        EntityID agentPosition = agent.getPosition();
        
        if (targetEntity instanceof Human) {
            Human human = (Human) targetEntity;
            if (!human.isPositionDefined()) return null;
            
            if (human.isHPDefined() && human.getHP() == 0) {
                System.err.println("[救护车] ❌ 平民 " + human.getID() + " 已死亡，放弃任务");
                this.target = null;
                return null;
            }
            
            if (hasBlockadeOnPath(agentPosition, human.getPosition())) {
                return null;
            }
            
            EntityID targetPosition = human.getPosition();
            
            if (agentPosition.getValue() == targetPosition.getValue()) {
                Human freshHuman = (Human) this.worldInfo.getEntity(targetID);
                if (freshHuman == null) {
                    this.target = null;
                    return null;
                }
                
                // 被掩埋的平民：放弃目标，让救护车寻找其他任务
                if (freshHuman.isBuriednessDefined() && freshHuman.getBuriedness() > 0) {
                    System.err.println("[救护车] ⚠️ 平民 " + targetID + " 仍被掩埋 (埋压度=" + freshHuman.getBuriedness() + ")，放弃等待");
                    this.target = null;
                    return null;
                }
                
                if (freshHuman.getStandardURN() == CIVILIAN) {
                    if (freshHuman.isDamageDefined() && freshHuman.getDamage() > 0) {
                        System.err.println("╔══════════════════════════════════════════════════════════════╗");
                        System.err.println("║  [救护车] 📦 装载受伤平民: " + freshHuman.getID() + " 伤害=" + freshHuman.getDamage());
                        System.err.println("╚══════════════════════════════════════════════════════════════╝");
                        EntityID nearestRefuge = findNearestRefuge(agentPosition);
                        if (nearestRefuge != null) {
                            this.target = nearestRefuge;
                            System.err.println("[救护车] 🏥 装载完成，前往避难所: " + nearestRefuge);
                        }
                        return new ActionLoad(freshHuman.getID());
                    } else {
                        System.err.println("[救护车] ⚠️ 平民 " + freshHuman.getID() + " 已挖出但未受伤，无需装载");
                        this.target = null;
                        return null;
                    }
                }
                
                System.err.println("[救护车] ⚠️ 目标 " + freshHuman.getID() + " 不是平民，放弃");
                this.target = null;
                return null;
                
            } else {
                List<EntityID> path = pathPlanning.getResult(agentPosition, targetPosition);
                if (path != null && path.size() > 0) {
                    System.err.println("[救护车] 📍 移动到平民位置: " + targetPosition);
                    return new ActionMove(path);
                }
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
            List<EntityID> path = pathPlanning.getResult(agentPosition, targetEntity.getID());
            if (path != null && path.size() > 0) {
                return new ActionMove(path);
            }
        }
        return null;
    }
    
    private boolean hasBlockadeOnPath(EntityID from, EntityID to) {
        List<EntityID> path = this.pathPlanning.getResult(from, to);
        if (path == null) return true;
        
        for (EntityID step : path) {
            StandardEntity e = this.worldInfo.getEntity(step);
            if (e instanceof Road) {
                Road road = (Road) e;
                if (road.isBlockadesDefined() && !road.getBlockades().isEmpty()) {
                    requestClear(road);
                    System.err.println("[救护车] 🚧 发现路障 " + step + "，请求警察清理");
                    return true;
                }
            }
        }
        return false;
    }
    
    private void requestClear(Road road) {
        if (this.msgManager == null) return;
        
        AmbulanceTeam agent = (AmbulanceTeam) this.agentInfo.me();
        EntityID roadId = road.getID();
        
        MessageAmbulanceTeam msg = new MessageAmbulanceTeam(
                true,
                agent,
                MessageAmbulanceTeam.ACTION_MOVE,
                roadId);
        this.msgManager.addMessage(msg);
        
        System.err.println("╔══════════════════════════════════════════════════════════════╗");
        System.err.println("║  [救护车] 🚧 发现路障，发送开路请求！                         ║");
        System.err.println("║  救护车 ID: " + agent.getID());
        System.err.println("║  需要清理的道路: " + roadId);
        System.err.println("║  路障数量: " + (road.isBlockadesDefined() ? road.getBlockades().size() : 0));
        System.err.println("╚══════════════════════════════════════════════════════════════╝");
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