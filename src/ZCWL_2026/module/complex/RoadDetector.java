package ZCWL_2026.module.complex;

import adf.core.component.module.algorithm.PathPlanning;
import adf.core.component.communication.CommunicationMessage;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.MessageUtil;
import adf.core.agent.communication.standard.bundle.centralized.CommandPolice;
import adf.core.agent.communication.standard.bundle.information.MessageAmbulanceTeam;
import adf.core.agent.communication.standard.bundle.information.MessageFireBrigade;
import adf.core.agent.communication.standard.bundle.information.MessagePoliceForce;
import adf.core.agent.communication.standard.bundle.information.MessageRoad;
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

public class RoadDetector extends adf.core.component.module.complex.RoadDetector {

    private Set<EntityID> targetAreas;
    private Set<EntityID> priorityRoads;
    private PathPlanning pathPlanning;
    private EntityID result;

    public RoadDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        
        // 初始化路径规划模块
        switch (si.getMode()) {
            case PRECOMPUTATION_PHASE:
                this.pathPlanning = moduleManager.getModule("RoadDetector.PathPlanning", "ZCWL_2026.module.algorithm.PathPlanning");
                break;
            case PRECOMPUTED:
                this.pathPlanning = moduleManager.getModule("RoadDetector.PathPlanning", "ZCWL_2026.module.algorithm.PathPlanning");
                break;
            case NON_PRECOMPUTE:
                this.pathPlanning = moduleManager.getModule("RoadDetector.PathPlanning", "ZCWL_2026.module.algorithm.PathPlanning");
                break;
        }
        registerModule(this.pathPlanning);
        
        this.result = null;
    }

    @Override
    public RoadDetector calc() {
        if (this.result == null) {
            EntityID positionID = this.agentInfo.getPosition();
            
            // 如果当前位置就是目标区域
            if (this.targetAreas.contains(positionID)) {
                this.result = positionID;
                return this;
            }
            
            // 清理不在目标区域的优先道路
            List<EntityID> removeList = new ArrayList<>(this.priorityRoads.size());
            for (EntityID id : this.priorityRoads) {
                if (!this.targetAreas.contains(id)) {
                    removeList.add(id);
                }
            }
            this.priorityRoads.removeAll(removeList);
            
            // 优先选择优先道路作为目标
            if (this.priorityRoads.size() > 0) {
                this.pathPlanning.setFrom(positionID);
                this.pathPlanning.setDestination(this.targetAreas);
                List<EntityID> path = this.pathPlanning.calc().getResult();
                if (path != null && path.size() > 0) {
                    this.result = path.get(path.size() - 1);
                }
                return this;
            }
            
            // 选择普通目标道路
            this.pathPlanning.setFrom(positionID);
            this.pathPlanning.setDestination(this.targetAreas);
            List<EntityID> path = this.pathPlanning.calc().getResult();
            if (path != null && path.size() > 0) {
                this.result = path.get(path.size() - 1);
            }
        }
        return this;
    }

    @Override
    public EntityID getTarget() {
        return this.result;
    }

    @Override
    public RoadDetector precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        if (this.getCountPrecompute() >= 2) {
            return this;
        }
        return this;
    }

    @Override
    public RoadDetector resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (this.getCountResume() >= 2) {
            return this;
        }
        
        // 初始化目标道路（建筑周边道路）
        this.targetAreas = new HashSet<>();
        for (StandardEntity e : this.worldInfo.getEntitiesOfType(REFUGE, BUILDING, GAS_STATION)) {
            for (EntityID id : ((Building) e).getNeighbours()) {
                StandardEntity neighbour = this.worldInfo.getEntity(id);
                if (neighbour instanceof Road) {
                    this.targetAreas.add(id);
                }
            }
        }
        
        // 初始化优先道路（避难所周边道路）
        this.priorityRoads = new HashSet<>();
        for (StandardEntity e : this.worldInfo.getEntitiesOfType(REFUGE)) {
            for (EntityID id : ((Building) e).getNeighbours()) {
                StandardEntity neighbour = this.worldInfo.getEntity(id);
                if (neighbour instanceof Road) {
                    this.priorityRoads.add(id);
                }
            }
        }
        
        return this;
    }

    @Override
    public RoadDetector preparate() {
        super.preparate();
        if (this.getCountPreparate() >= 2) {
            return this;
        }
        
        // 初始化目标道路（建筑周边道路）
        this.targetAreas = new HashSet<>();
        for (StandardEntity e : this.worldInfo.getEntitiesOfType(REFUGE, BUILDING, GAS_STATION)) {
            for (EntityID id : ((Building) e).getNeighbours()) {
                StandardEntity neighbour = this.worldInfo.getEntity(id);
                if (neighbour instanceof Road) {
                    this.targetAreas.add(id);
                }
            }
        }
        
        // 初始化优先道路（避难所周边道路）
        this.priorityRoads = new HashSet<>();
        for (StandardEntity e : this.worldInfo.getEntitiesOfType(REFUGE)) {
            for (EntityID id : ((Building) e).getNeighbours()) {
                StandardEntity neighbour = this.worldInfo.getEntity(id);
                if (neighbour instanceof Road) {
                    this.priorityRoads.add(id);
                }
            }
        }
        
        return this;
    }

    @Override
    public RoadDetector updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() >= 2) {
            return this;
        }
        
        // 检查当前目标是否已完成清理
        if (this.result != null) {
            if (this.agentInfo.getPosition().equals(this.result)) {
                StandardEntity entity = this.worldInfo.getEntity(this.result);
                if (entity instanceof Building) {
                    this.result = null;
                } else if (entity instanceof Road) {
                    Road road = (Road) entity;
                    if (!road.isBlockadesDefined() || road.getBlockades().isEmpty()) {
                        this.targetAreas.remove(this.result);
                        this.result = null;
                    }
                }
            }
        }
        
        // 处理所有接收到的消息
        Set<EntityID> changedEntities = this.worldInfo.getChanged().getChangedEntities();
        for (CommunicationMessage message : messageManager.getReceivedMessageList()) {
            Class<? extends CommunicationMessage> messageClass = message.getClass();
            if (messageClass == MessageAmbulanceTeam.class) {
                this.reflectMessage((MessageAmbulanceTeam) message);
            } else if (messageClass == MessageFireBrigade.class) {
                this.reflectMessage((MessageFireBrigade) message);
            } else if (messageClass == MessageRoad.class) {
                this.reflectMessage((MessageRoad) message, changedEntities);
            } else if (messageClass == MessagePoliceForce.class) {
                this.reflectMessage((MessagePoliceForce) message);
            } else if (messageClass == CommandPolice.class) {
                this.reflectMessage((CommandPolice) message);
            }
        }
        
        // 更新道路状态，移除已清理的道路
        for (EntityID id : this.worldInfo.getChanged().getChangedEntities()) {
            StandardEntity entity = this.worldInfo.getEntity(id);
            if (entity instanceof Road) {
                Road road = (Road) entity;
                if (!road.isBlockadesDefined() || road.getBlockades().isEmpty()) {
                    this.targetAreas.remove(id);
                }
            }
        }
        
        return this;
    }

    /**
     * 处理道路消息
     */
    private void reflectMessage(MessageRoad messageRoad, Collection<EntityID> changedEntities) {
        if (messageRoad.isBlockadeDefined() && !changedEntities.contains(messageRoad.getBlockadeID())) {
            MessageUtil.reflectMessage(this.worldInfo, messageRoad);
        }
        if (messageRoad.isPassable()) {
            this.targetAreas.remove(messageRoad.getRoadID());
        }
    }

    /**
     * 处理救护车消息
     */
    private void reflectMessage(MessageAmbulanceTeam messageAmbulanceTeam) {
        if (messageAmbulanceTeam.getPosition() == null) {
            return;
        }
        
        // 救援或装载动作：移除相关建筑周边道路
        if (messageAmbulanceTeam.getAction() == MessageAmbulanceTeam.ACTION_RESCUE ||
            messageAmbulanceTeam.getAction() == MessageAmbulanceTeam.ACTION_LOAD) {
            StandardEntity position = this.worldInfo.getEntity(messageAmbulanceTeam.getPosition());
            if (position != null && position instanceof Building) {
                this.targetAreas.removeAll(((Building) position).getNeighbours());
            }
        }
        // 移动动作：添加目标建筑周边道路到优先列表
        else if (messageAmbulanceTeam.getAction() == MessageAmbulanceTeam.ACTION_MOVE) {
            if (messageAmbulanceTeam.getTargetID() == null) {
                return;
            }
            StandardEntity target = this.worldInfo.getEntity(messageAmbulanceTeam.getTargetID());
            if (target instanceof Building) {
                for (EntityID id : ((Building) target).getNeighbours()) {
                    StandardEntity neighbour = this.worldInfo.getEntity(id);
                    if (neighbour instanceof Road) {
                        this.priorityRoads.add(id);
                    }
                }
            } else if (target instanceof Human) {
                Human human = (Human) target;
                if (human.isPositionDefined()) {
                    StandardEntity position = this.worldInfo.getPosition(human);
                    if (position instanceof Building) {
                        for (EntityID id : ((Building) position).getNeighbours()) {
                            StandardEntity neighbour = this.worldInfo.getEntity(id);
                            if (neighbour instanceof Road) {
                                this.priorityRoads.add(id);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 处理消防队员消息
     */
    private void reflectMessage(MessageFireBrigade messageFireBrigade) {
        if (messageFireBrigade.getTargetID() == null) {
            return;
        }
        
        // 补水动作：添加目标建筑周边道路到优先列表
        if (messageFireBrigade.getAction() == MessageFireBrigade.ACTION_REFILL) {
            StandardEntity target = this.worldInfo.getEntity(messageFireBrigade.getTargetID());
            if (target instanceof Building) {
                for (EntityID id : ((Building) target).getNeighbours()) {
                    StandardEntity neighbour = this.worldInfo.getEntity(id);
                    if (neighbour instanceof Road) {
                        this.priorityRoads.add(id);
                    }
                }
            } else if (target.getStandardURN() == HYDRANT) {
                this.priorityRoads.add(target.getID());
                this.targetAreas.add(target.getID());
            }
        }
    }

    /**
     * 处理警察消息
     */
    private void reflectMessage(MessagePoliceForce messagePoliceForce) {
        if (messagePoliceForce.getAction() == MessagePoliceForce.ACTION_CLEAR) {
            // 排除自身消息
            if (messagePoliceForce.getAgentID().getValue() != this.agentInfo.getID().getValue()) {
                if (messagePoliceForce.isTargetDefined()) {
                    EntityID targetID = messagePoliceForce.getTargetID();
                    if (targetID == null) {
                        return;
                    }
                    StandardEntity entity = this.worldInfo.getEntity(targetID);
                    if (entity == null) {
                        return;
                    }

                    // 清理区域目标：移除目标并重置当前结果（ID小的让给ID大的）
                    if (entity instanceof Area) {
                        this.targetAreas.remove(targetID);
                        if (this.result != null && this.result.getValue() == targetID.getValue()) {
                            if (this.agentInfo.getID().getValue() < messagePoliceForce.getAgentID().getValue()) {
                                this.result = null;
                            }
                        }
                    }
                    // 清理路障：移除路障所在道路
                    else if (entity.getStandardURN() == BLOCKADE) {
                        EntityID position = ((Blockade) entity).getPosition();
                        this.targetAreas.remove(position);
                        if (this.result != null && this.result.getValue() == position.getValue()) {
                            if (this.agentInfo.getID().getValue() < messagePoliceForce.getAgentID().getValue()) {
                                this.result = null;
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 处理警察命令消息
     */
    private void reflectMessage(CommandPolice commandPolice) {
        boolean flag = false;
        // 检查命令是否针对当前智能体或广播
        if (commandPolice.isToIDDefined() && this.agentInfo.getID().getValue() == commandPolice.getToID().getValue()) {
            flag = true;
        } else if (commandPolice.isBroadcast()) {
            flag = true;
        }
        
        if (flag && commandPolice.getAction() == CommandPolice.ACTION_CLEAR) {
            if (commandPolice.getTargetID() == null) {
                return;
            }
            StandardEntity target = this.worldInfo.getEntity(commandPolice.getTargetID());
            // 清理区域：添加到优先列表
            if (target instanceof Area) {
                this.priorityRoads.add(target.getID());
                this.targetAreas.add(target.getID());
            }
            // 清理路障：添加路障所在道路到优先列表
            else if (target.getStandardURN() == BLOCKADE) {
                Blockade blockade = (Blockade) target;
                if (blockade.isPositionDefined()) {
                    this.priorityRoads.add(blockade.getPosition());
                    this.targetAreas.add(blockade.getPosition());
                }
            }
        }
    }
}
