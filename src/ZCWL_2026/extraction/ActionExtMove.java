package ZCWL_2026.extraction;

import adf.core.agent.action.Action;
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

public class ActionExtMove extends adf.core.component.extaction.ExtAction {

    private PathPlanning pathPlanning;
    private int thresholdRest;
    private int kernelTime;
    private EntityID target;
    private MessageManager msgManager;
    
    private Set<EntityID> reportedVictims = new HashSet<>();

    public ActionExtMove(AgentInfo agentInfo, WorldInfo worldInfo, ScenarioInfo scenarioInfo,
                          ModuleManager moduleManager, DevelopData developData) {
        super(agentInfo, worldInfo, scenarioInfo, moduleManager, developData);
        this.target = null;
        this.thresholdRest = developData.getInteger("ActionExtMove.rest", 100);

        switch (scenarioInfo.getMode()) {
            case PRECOMPUTATION_PHASE:
            case PRECOMPUTED:
            case NON_PRECOMPUTE:
                this.pathPlanning = moduleManager.getModule(
                    "ActionExtMove.PathPlanning",
                    "ZCWL_2026.module.algorithm.PathPlanning");
                break;
        }
        
        String agentName = "";
        if (agentInfo.me().getStandardURN() == FIRE_BRIGADE) agentName = "消防车";
        else if (agentInfo.me().getStandardURN() == AMBULANCE_TEAM) agentName = "救护车";
        else if (agentInfo.me().getStandardURN() == POLICE_FORCE) agentName = "警车";
        
        System.err.println("[ZCWL_2026] " + agentName + " ID:" + agentInfo.getID() + " 移动动作模块已加载");
    }

    @Override
    public ExtAction precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        if (this.getCountPrecompute() >= 2) return this;
        this.pathPlanning.precompute(precomputeData);
        try {
            this.kernelTime = this.scenarioInfo.getKernelTimesteps();
        } catch (NoSuchConfigOptionException e) {
            this.kernelTime = -1;
        }
        return this;
    }

    @Override
    public ExtAction resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (this.getCountResume() >= 2) return this;
        this.pathPlanning.resume(precomputeData);
        try {
            this.kernelTime = this.scenarioInfo.getKernelTimesteps();
        } catch (NoSuchConfigOptionException e) {
            this.kernelTime = -1;
        }
        return this;
    }

    @Override
    public ExtAction preparate() {
        super.preparate();
        if (this.getCountPreparate() >= 2) return this;
        this.pathPlanning.preparate();
        try {
            this.kernelTime = this.scenarioInfo.getKernelTimesteps();
        } catch (NoSuchConfigOptionException e) {
            this.kernelTime = -1;
        }
        return this;
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
        StandardEntity entity = this.worldInfo.getEntity(target);
        if (entity != null) {
            if (entity.getStandardURN().equals(BLOCKADE)) {
                entity = this.worldInfo.getEntity(((Blockade) entity).getPosition());
            } else if (entity instanceof Human) {
                entity = this.worldInfo.getPosition((Human) entity);
            }
            if (entity != null && entity instanceof Area) {
                this.target = entity.getID();
            }
        }
        return this;
    }

    @Override
    public ExtAction calc() {
        this.result = null;
        Human agent = (Human) this.agentInfo.me();
        StandardEntityURN agentType = agent.getStandardURN();

        // 警察：检查当前位置建筑内是否有被困人员
        if (agentType == POLICE_FORCE && this.msgManager != null) {
            EntityID currentPos = agent.getPosition();
            StandardEntity currentEntity = this.worldInfo.getEntity(currentPos);
            if (currentEntity instanceof Building) {
                // 扫描建筑内所有实体
                Collection<StandardEntity> entitiesInBuilding = this.worldInfo.getObjectsInRange(currentPos, 100);
                for (StandardEntity e : entitiesInBuilding) {
                    if (e instanceof Human) {
                        Human human = (Human) e;
                        if (human.isBuriednessDefined() && human.getBuriedness() > 0 &&
                            human.isPositionDefined() && human.getPosition().equals(currentPos)) {
                            if (!reportedVictims.contains(human.getID())) {
                                sendRescueRequest(human);
                                reportedVictims.add(human.getID());
                            }
                        }
                    }
                }
            }
        }

        if (this.needRest(agent)) {
            this.result = this.calcRest(agent, this.pathPlanning, this.target);
            if (this.result != null) {
                return this;
            }
        }
        
        if (this.target == null) {
            return this;
        }
        
        EntityID currentPos = agent.getPosition();
        
        if (currentPos.getValue() == this.target.getValue()) {
            return this;
        }
        
        this.pathPlanning.setFrom(currentPos);
        this.pathPlanning.setDestination(this.target);
        List<EntityID> path = this.pathPlanning.calc().getResult();
        
        if (path != null && path.size() > 0) {
            String agentName = "";
            if (agent.getStandardURN() == FIRE_BRIGADE) agentName = "消防车";
            else if (agent.getStandardURN() == AMBULANCE_TEAM) agentName = "救护车";
            else if (agent.getStandardURN() == POLICE_FORCE) agentName = "警车";
            
            System.err.println("[ZCWL_2026] " + agentName + " ID:" + agent.getID() + 
                               " 📍 移动到: " + this.target + ", 路径长度: " + path.size());
            this.result = new ActionMove(path);
        } else {
            System.err.println("[ZCWL_2026] 无法计算路径: 从 " + currentPos + " 到 " + this.target);
        }
        return this;
    }

    /**
     * 根据被困者类型发送对应消息
     */
    private void sendRescueRequest(Human victim) {
        if (this.msgManager == null) return;
        
        PoliceForce agent = (PoliceForce) this.agentInfo.me();
        EntityID victimId = victim.getID();
        EntityID victimPosition = victim.getPosition();
        
        CommunicationMessage msg = null;
        if (victim instanceof Civilian) {
            msg = new MessageCivilian(true, (Civilian) victim);
        } else if (victim instanceof PoliceForce) {
            msg = new MessagePoliceForce(true, (PoliceForce) victim, MessagePoliceForce.ACTION_REST, victimPosition);
        } else if (victim instanceof FireBrigade) {
            msg = new MessageFireBrigade(true, (FireBrigade) victim, MessageFireBrigade.ACTION_REST, victimPosition);
        } else if (victim instanceof AmbulanceTeam) {
            msg = new MessageAmbulanceTeam(true, (AmbulanceTeam) victim, MessageAmbulanceTeam.ACTION_REST, victimPosition);
        } else {
            System.err.println("[警察] 无法识别被困者类型，跳过发送");
            return;
        }
        
        this.msgManager.addMessage(msg);
        
        String typeName = "";
        if (victim.getStandardURN() == CIVILIAN) typeName = "平民";
        else if (victim.getStandardURN() == POLICE_FORCE) typeName = "警察";
        else if (victim.getStandardURN() == FIRE_BRIGADE) typeName = "消防员";
        else if (victim.getStandardURN() == AMBULANCE_TEAM) typeName = "救护车";
        else typeName = victim.getStandardURN().toString();
        
        System.err.println("╔══════════════════════════════════════════════════════════════╗");
        System.err.println("║  [警察] 🚨 发现被困人员，发送救援请求！                       ║");
        System.err.println("║  警察 ID: " + agent.getID());
        System.err.println("║  被困" + typeName + ": " + victimId);
        System.err.println("║  被困位置: " + victimPosition);
        System.err.println("║  埋压度: " + victim.getBuriedness());
        System.err.println("╚══════════════════════════════════════════════════════════════╝");
    }

    private boolean needRest(Human agent) {
        int hp = agent.getHP();
        int damage = agent.getDamage();
        if (hp == 0 || damage == 0) {
            return false;
        }
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

    private Action calcRest(Human human, PathPlanning pathPlanning, EntityID target) {
        EntityID position = human.getPosition();
        Collection<EntityID> refuges = this.worldInfo.getEntityIDsOfType(REFUGE);
        int currentSize = refuges.size();
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
                    if (target == null) {
                        break;
                    }
                }
                EntityID refugeID = path.get(path.size() - 1);
                pathPlanning.setFrom(refugeID);
                pathPlanning.setDestination(target);
                List<EntityID> fromRefugeToTarget = pathPlanning.calc().getResult();
                if (fromRefugeToTarget != null && fromRefugeToTarget.size() > 0) {
                    return new ActionMove(path);
                }
                refuges.remove(refugeID);
                if (currentSize == refuges.size()) {
                    break;
                }
                currentSize = refuges.size();
            } else {
                break;
            }
        }
        return firstResult != null ? new ActionMove(firstResult) : null;
    }
}