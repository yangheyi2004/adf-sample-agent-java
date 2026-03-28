package ZCWL_2026.extraction;

import adf.core.agent.action.Action;
import adf.core.agent.action.ambulance.ActionRescue;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.action.common.ActionRest;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.centralized.CommandAmbulance;
import adf.core.agent.communication.standard.bundle.information.*;
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

public class ActionFireRescue extends ExtAction {

    private PathPlanning pathPlanning;
    private int thresholdRest;
    private int kernelTime;
    private EntityID target;
    private MessageManager msgManager;
    private Set<EntityID> reportedVictims;

    public ActionFireRescue(AgentInfo agentInfo, WorldInfo worldInfo, ScenarioInfo scenarioInfo,
                             ModuleManager moduleManager, DevelopData developData) {
        super(agentInfo, worldInfo, scenarioInfo, moduleManager, developData);
        this.target = null;
        this.thresholdRest = developData.getInteger("ActionFireRescue.rest", 100);
        this.reportedVictims = new HashSet<>();

        switch (scenarioInfo.getMode()) {
            case PRECOMPUTATION_PHASE:
            case PRECOMPUTED:
            case NON_PRECOMPUTE:
                this.pathPlanning = moduleManager.getModule(
                        "ActionFireRescue.PathPlanning",
                        "ZCWL_2026.module.algorithm.PathPlanning");
                break;
        }
        
        System.err.println("[消防车] ID:" + agentInfo.getID() + " 救援动作模块已加载");
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
        return this;
    }

    @Override
    public ExtAction calc() {
        this.result = null;
        FireBrigade agent = (FireBrigade) this.agentInfo.me();

        if (this.needRest(agent)) {
            this.result = this.calcRefugeAction(agent, this.pathPlanning, this.target);
            if (this.result != null) return this;
        }

        if (this.target == null) return this;
        
        Human targetHuman = (Human) this.worldInfo.getEntity(this.target);
        if (targetHuman == null) return this;
        
        // 已被挖出或死亡
        boolean isUnburied = (targetHuman.isBuriednessDefined() && targetHuman.getBuriedness() == 0);
        boolean isDead = (targetHuman.isHPDefined() && targetHuman.getHP() == 0);
        
        if (isUnburied || isDead) {
            if (!reportedVictims.contains(this.target)) {
                reportedVictims.add(this.target);
                
                // 只有平民且受伤才指派救护车
                if (targetHuman.getStandardURN() == CIVILIAN && isUnburied && !isDead) {
                    if (targetHuman.isDamageDefined() && targetHuman.getDamage() > 0) {
                        forceAssignAmbulance(targetHuman);
                        System.err.println("╔══════════════════════════════════════════════════════════════╗");
                        System.err.println("║  [消防车] 🚨 强制指派救护车！                                 ║");
                        System.err.println("║  平民 " + this.target + " 已挖出且受伤，强制指派最近的救护车装载");
                        System.err.println("║  伤害值: " + targetHuman.getDamage());
                        System.err.println("╚══════════════════════════════════════════════════════════════╝");
                    } else {
                        System.err.println("[消防车] 平民 " + this.target + " 已挖出但未受伤，无需救护车");
                    }
                } else if (targetHuman.getStandardURN() != CIVILIAN) {
                    String typeName = "";
                    if (targetHuman.getStandardURN() == POLICE_FORCE) typeName = "警察";
                    else if (targetHuman.getStandardURN() == FIRE_BRIGADE) typeName = "消防员";
                    else if (targetHuman.getStandardURN() == AMBULANCE_TEAM) typeName = "救护车";
                    else typeName = targetHuman.getStandardURN().toString();
                    System.err.println("[消防车] ✅ 救援完成: " + typeName + " " + this.target);
                } else if (isDead) {
                    System.err.println("[消防车] ❌ 平民 " + this.target + " 已死亡，放弃");
                }
            }
            this.target = null;
            return this;
        }
        
        // 救援任务（0优先级）
        this.result = this.calcRescue(agent, this.pathPlanning, this.target);
        return this;
    }

    /**
     * 强制指派救护车（仅当平民受伤时）
     */
    private void forceAssignAmbulance(Human victim) {
        if (this.msgManager == null) return;
        
        if (!victim.isDamageDefined() || victim.getDamage() == 0) {
            System.err.println("[消防车] 平民 " + victim.getID() + " 未受伤，不指派救护车");
            return;
        }
        
        AmbulanceTeam agent = (AmbulanceTeam) this.agentInfo.me();
        EntityID nearestAmbulance = findNearestAmbulance(victim.getPosition());
        
        // 发送平民消息（消防员分配器会处理）
        if (victim instanceof Civilian) {
            MessageCivilian msg = new MessageCivilian(true, (Civilian) victim);
            this.msgManager.addMessage(msg);
        } else {
            // 对于非平民，发送对应的消息（但一般不会发生）
            System.err.println("[消防车] 只对平民指派救护车");
            return;
        }
        
        if (nearestAmbulance != null) {
            CommandAmbulance command = new CommandAmbulance(
                    true,
                    nearestAmbulance,
                    victim.getID(),
                    CommandAmbulance.ACTION_LOAD);
            this.msgManager.addMessage(command);
            System.err.println("[消防车] 📡 强制指派救护车 " + nearestAmbulance + " 装载平民 " + victim.getID());
        }
    }
    
    private EntityID findNearestAmbulance(EntityID victimPosition) {
        EntityID nearest = null;
        double minDist = Double.MAX_VALUE;
        for (StandardEntity e : this.worldInfo.getEntitiesOfType(AMBULANCE_TEAM)) {
            AmbulanceTeam ambulance = (AmbulanceTeam) e;
            if (ambulance.isPositionDefined()) {
                double dist = this.worldInfo.getDistance(ambulance.getPosition(), victimPosition);
                if (dist < minDist) {
                    minDist = dist;
                    nearest = ambulance.getID();
                }
            }
        }
        return nearest;
    }

    private Action calcRescue(FireBrigade agent, PathPlanning pathPlanning, EntityID targetID) {
        StandardEntity targetEntity = this.worldInfo.getEntity(targetID);
        if (targetEntity == null) return null;
        
        EntityID agentPosition = agent.getPosition();
        
        if (targetEntity instanceof Human) {
            Human human = (Human) targetEntity;
            if (!human.isPositionDefined()) return null;
            if (human.isHPDefined() && human.getHP() == 0) return null;
            
            EntityID targetPosition = human.getPosition();
            
            // 路径上有路障，发送开路请求
            if (hasBlockadeOnPath(agentPosition, targetPosition)) {
                return null;
            }
            
            if (agentPosition.getValue() == targetPosition.getValue()) {
                if (human.isBuriednessDefined() && human.getBuriedness() > 0) {
                    System.err.println("[消防车] 🆘 救援被困平民: " + human.getID());
                    return new ActionRescue(human);
                }
            } else {
                List<EntityID> path = pathPlanning.getResult(agentPosition, targetPosition);
                if (path != null && path.size() > 0) {
                    System.err.println("[消防车] 📍 移动到被困平民位置: " + targetPosition);
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
                    System.err.println("[消防车] 🚧 发现路障 " + step + "，请求警察清理");
                    return true;
                }
            }
        }
        return false;
    }
    
    private void requestClear(Road road) {
        if (this.msgManager == null) return;
        
        FireBrigade agent = (FireBrigade) this.agentInfo.me();
        EntityID roadId = road.getID();
        
        MessageFireBrigade msg = new MessageFireBrigade(
                true,
                agent,
                MessageFireBrigade.ACTION_RESCUE,
                roadId);
        this.msgManager.addMessage(msg);
        
        System.err.println("╔══════════════════════════════════════════════════════════════╗");
        System.err.println("║  [消防车] 🚧 发现路障，发送开路请求！                         ║");
        System.err.println("║  消防车 ID: " + agent.getID());
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

    private Action calcRefugeAction(Human human, PathPlanning pathPlanning, EntityID target) {
        EntityID position = human.getPosition();
        Collection<EntityID> refuges = this.worldInfo.getEntityIDsOfType(REFUGE);
        if (refuges.contains(position)) {
            return new ActionRest();
        }
        List<EntityID> firstResult = null;
        while (refuges.size() > 0) {
            pathPlanning.setFrom(position);
            pathPlanning.setDestination(refuges);
            List<EntityID> path = pathPlanning.calc().getResult();
            if (path != null && path.size() > 0) {
                if (firstResult == null) {
                    firstResult = new ArrayList<>(path);
                    if (target == null) break;
                }
                EntityID refugeID = path.get(path.size() - 1);
                pathPlanning.setFrom(refugeID);
                pathPlanning.setDestination(target);
                List<EntityID> fromRefugeToTarget = pathPlanning.calc().getResult();
                if (fromRefugeToTarget != null && fromRefugeToTarget.size() > 0) {
                    return new ActionMove(path);
                }
                refuges.remove(refugeID);
            } else {
                break;
            }
        }
        return firstResult != null ? new ActionMove(firstResult) : null;
    }

    @Override
    public ExtAction precompute(PrecomputeData pd) { return this; }
    @Override
    public ExtAction resume(PrecomputeData pd) { return this; }
    @Override
    public ExtAction preparate() { return this; }
}