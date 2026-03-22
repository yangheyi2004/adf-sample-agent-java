package ZCWL_2026.module.complex;

import adf.core.component.communication.CommunicationMessage;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.MessageUtil;
import adf.core.agent.communication.standard.bundle.centralized.CommandPolice;
import adf.core.agent.communication.standard.bundle.centralized.MessageReport;
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

public class PoliceTargetAllocator extends adf.core.component.module.complex.PoliceTargetAllocator {

    private Collection<EntityID> priorityAreas;
    private Collection<EntityID> targetAreas;
    private Map<EntityID, PoliceForceInfo> agentInfoMap;

    public PoliceTargetAllocator(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.priorityAreas = new HashSet<>();
        this.targetAreas = new HashSet<>();
        this.agentInfoMap = new HashMap<>();
    }

    @Override
    public PoliceTargetAllocator resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (this.getCountResume() >= 2) {
            return this;
        }
        
        // 初始化警察状态信息
        for (EntityID id : this.worldInfo.getEntityIDsOfType(StandardEntityURN.POLICE_FORCE)) {
            this.agentInfoMap.put(id, new PoliceForceInfo(id));
        }
        
        // 初始化目标区域（建筑周边道路）
        for (StandardEntity e : this.worldInfo.getEntitiesOfType(REFUGE, BUILDING, GAS_STATION)) {
            for (EntityID id : ((Building) e).getNeighbours()) {
                StandardEntity neighbour = this.worldInfo.getEntity(id);
                if (neighbour instanceof Road) {
                    this.targetAreas.add(id);
                }
            }
        }
        
        // 初始化优先区域（避难所周边道路）
        for (StandardEntity e : this.worldInfo.getEntitiesOfType(REFUGE)) {
            for (EntityID id : ((Building) e).getNeighbours()) {
                StandardEntity neighbour = this.worldInfo.getEntity(id);
                if (neighbour instanceof Road) {
                    this.priorityAreas.add(id);
                }
            }
        }
        
        return this;
    }

    @Override
    public PoliceTargetAllocator preparate() {
        super.preparate();
        if (this.getCountPrecompute() >= 2) {
            return this;
        }
        
        // 初始化警察状态信息
        for (EntityID id : this.worldInfo.getEntityIDsOfType(StandardEntityURN.POLICE_FORCE)) {
            this.agentInfoMap.put(id, new PoliceForceInfo(id));
        }
        
        // 初始化目标区域（建筑周边道路）
        for (StandardEntity e : this.worldInfo.getEntitiesOfType(REFUGE, BUILDING, GAS_STATION)) {
            for (EntityID id : ((Building) e).getNeighbours()) {
                StandardEntity neighbour = this.worldInfo.getEntity(id);
                if (neighbour instanceof Road) {
                    this.targetAreas.add(id);
                }
            }
        }
        
        // 初始化优先区域（避难所周边道路）
        for (StandardEntity e : this.worldInfo.getEntitiesOfType(REFUGE)) {
            for (EntityID id : ((Building) e).getNeighbours()) {
                StandardEntity neighbour = this.worldInfo.getEntity(id);
                if (neighbour instanceof Road) {
                    this.priorityAreas.add(id);
                }
            }
        }
        
        return this;
    }

    @Override
    public Map<EntityID, EntityID> getResult() {
        return this.convert(this.agentInfoMap);
    }

    @Override
    public PoliceTargetAllocator calc() {
        List<StandardEntity> agents = this.getActionAgents(this.agentInfoMap);
        Collection<EntityID> removes = new ArrayList<>();
        int currentTime = this.agentInfo.getTime();
        
        // 分配优先区域目标
        for (EntityID target : this.priorityAreas) {
            if (agents.size() > 0) {
                StandardEntity targetEntity = this.worldInfo.getEntity(target);
                if (targetEntity != null) {
                    agents.sort(new DistanceSorter(this.worldInfo, targetEntity));
                    StandardEntity result = agents.get(0);
                    agents.remove(0);
                    
                    PoliceForceInfo info = this.agentInfoMap.get(result.getID());
                    if (info != null) {
                        info.canNewAction = false;
                        info.target = target;
                        info.commandTime = currentTime;
                        this.agentInfoMap.put(result.getID(), info);
                        removes.add(target);
                    }
                }
            }
        }
        this.priorityAreas.removeAll(removes);
        
        // 准备普通目标区域列表
        List<StandardEntity> areas = new ArrayList<>();
        for (EntityID target : this.targetAreas) {
            StandardEntity targetEntity = this.worldInfo.getEntity(target);
            if (targetEntity != null) {
                areas.add(targetEntity);
            }
        }
        
        // 分配普通区域目标
        for (StandardEntity agent : agents) {
            if (areas.size() > 0) {
                areas.sort(new DistanceSorter(this.worldInfo, agent));
                StandardEntity result = areas.get(0);
                areas.remove(0);
                
                this.targetAreas.remove(result.getID());
                PoliceForceInfo info = this.agentInfoMap.get(agent.getID());
                if (info != null) {
                    info.canNewAction = false;
                    info.target = result.getID();
                    info.commandTime = currentTime;
                    this.agentInfoMap.put(agent.getID(), info);
                }
            }
        }
        
        return this;
    }

    @Override
    public PoliceTargetAllocator updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() >= 2) {
            return this;
        }
        
        int currentTime = this.agentInfo.getTime();
        
        // 处理道路消息
        for (CommunicationMessage message : messageManager.getReceivedMessageList(MessageRoad.class)) {
            MessageRoad mpf = (MessageRoad) message;
            MessageUtil.reflectMessage(this.worldInfo, mpf);
        }
        
        // 处理警察消息
        for (CommunicationMessage message : messageManager.getReceivedMessageList(MessagePoliceForce.class)) {
            MessagePoliceForce mpf = (MessagePoliceForce) message;
            MessageUtil.reflectMessage(this.worldInfo, mpf);
            
            PoliceForceInfo info = this.agentInfoMap.get(mpf.getAgentID());
            if (info == null) {
                info = new PoliceForceInfo(mpf.getAgentID());
            }
            
            if (currentTime >= info.commandTime + 2) {
                this.agentInfoMap.put(mpf.getAgentID(), this.update(info, mpf));
            }
        }
        
        // 处理命令消息
        for (CommunicationMessage message : messageManager.getReceivedMessageList(CommandPolice.class)) {
            CommandPolice command = (CommandPolice) message;
            if (command.getAction() == CommandPolice.ACTION_CLEAR && command.isBroadcast()) {
                this.priorityAreas.add(command.getTargetID());
                this.targetAreas.add(command.getTargetID());
            }
        }
        
        // 处理报告消息
        for (CommunicationMessage message : messageManager.getReceivedMessageList(MessageReport.class)) {
            MessageReport report = (MessageReport) message;
            PoliceForceInfo info = this.agentInfoMap.get(report.getSenderID());
            
            if (info != null && report.isDone()) {
                info.canNewAction = true;
                this.priorityAreas.remove(info.target);
                this.targetAreas.remove(info.target);
                info.target = null;
                this.agentInfoMap.put(info.agentID, info);
            }
        }
        
        return this;
    }

    /**
     * 根据警察消息更新状态信息
     */
    private PoliceForceInfo update(PoliceForceInfo info, MessagePoliceForce message) {
        // 处理埋压状态
        if (message.isBuriednessDefined() && message.getBuriedness() > 0) {
            info.canNewAction = false;
            if (info.target != null) {
                this.targetAreas.add(info.target);
                info.target = null;
            }
            return info;
        }
        
        // 处理休息动作
        if (message.getAction() == MessagePoliceForce.ACTION_REST) {
            info.canNewAction = true;
            if (info.target != null) {
                this.targetAreas.add(info.target);
                info.target = null;
            }
        }
        // 处理移动动作
        else if (message.getAction() == MessagePoliceForce.ACTION_MOVE) {
            if (message.getTargetID() != null) {
                StandardEntity entity = this.worldInfo.getEntity(message.getTargetID());
                if (entity != null && entity instanceof Area) {
                    if (info.target != null) {
                        StandardEntity targetEntity = this.worldInfo.getEntity(info.target);
                        if (targetEntity != null && targetEntity instanceof Area) {
                            if (message.getTargetID().getValue() == info.target.getValue()) {
                                info.canNewAction = false;
                            } else {
                                info.canNewAction = true;
                                this.targetAreas.add(info.target);
                                info.target = null;
                            }
                        } else {
                            info.canNewAction = true;
                            info.target = null;
                        }
                    } else {
                        info.canNewAction = true;
                    }
                } else {
                    info.canNewAction = true;
                    if (info.target != null) {
                        this.targetAreas.add(info.target);
                        info.target = null;
                    }
                }
            } else {
                info.canNewAction = true;
                if (info.target != null) {
                    this.targetAreas.add(info.target);
                    info.target = null;
                }
            }
        }
        // 处理清理动作
        else if (message.getAction() == MessagePoliceForce.ACTION_CLEAR) {
            info.canNewAction = false;
        }
        
        return info;
    }

    /**
     * 获取可执行任务的警察列表
     */
    private List<StandardEntity> getActionAgents(Map<EntityID, PoliceForceInfo> infoMap) {
        List<StandardEntity> result = new ArrayList<>();
        for (StandardEntity entity : this.worldInfo.getEntitiesOfType(StandardEntityURN.POLICE_FORCE)) {
            PoliceForceInfo info = infoMap.get(entity.getID());
            if (info != null && info.canNewAction && ((PoliceForce) entity).isPositionDefined()) {
                result.add(entity);
            }
        }
        return result;
    }

    /**
     * 转换警察信息映射为ID映射
     */
    private Map<EntityID, EntityID> convert(Map<EntityID, PoliceForceInfo> infoMap) {
        Map<EntityID, EntityID> result = new HashMap<>();
        for (EntityID id : infoMap.keySet()) {
            PoliceForceInfo info = infoMap.get(id);
            if (info != null && info.target != null) {
                result.put(id, info.target);
            }
        }
        return result;
    }

    /**
     * 警察状态信息类
     */
    private class PoliceForceInfo {
        EntityID agentID;
        EntityID target;
        boolean canNewAction;
        int commandTime;

        PoliceForceInfo(EntityID id) {
            agentID = id;
            target = null;
            canNewAction = true;
            commandTime = -1;
        }
    }

    /**
     * 距离排序器 - 按与参考实体的距离升序排列
     */
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
}
