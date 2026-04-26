package ZCWL_2026.centralized;

import adf.core.component.centralized.CommandPicker;
import adf.core.component.communication.CommunicationMessage;
import adf.core.agent.communication.standard.bundle.centralized.CommandFire;
import adf.core.agent.communication.standard.bundle.centralized.CommandScout;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

public class FireCommandPicker extends adf.core.component.centralized.CommandPicker {

    private int scoutDistance;
    private Collection<CommunicationMessage> messages;
    private Map<EntityID, EntityID> allocationData;

    public FireCommandPicker(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                              ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.messages = new ArrayList<>();
        this.allocationData = null;
        this.scoutDistance = developData.getInteger("FireCommandPicker.scoutDistance", 40000);
        System.err.println("[FireCommandPicker] 已初始化");
    }

    @Override
    public CommandPicker setAllocatorResult(Map<EntityID, EntityID> allocationData) {
        this.allocationData = allocationData;
        /*System.err.println("[FireCommandPicker] 收到分配结果，任务数: " + 
                           (allocationData != null ? allocationData.size() : 0));*/
        return this;
    }

    @Override
    public CommandPicker calc() {
        this.messages.clear();
        if (this.allocationData == null || this.allocationData.isEmpty()) {
           // System.err.println("[FireCommandPicker] 分配数据为空，不生成命令");
            return this;
        }

        int rescueCount = 0, extinguishCount = 0, scoutCount = 0;

        for (Map.Entry<EntityID, EntityID> entry : this.allocationData.entrySet()) {
            EntityID agentID = entry.getKey();
            EntityID targetID = entry.getValue();

            StandardEntity agent = this.worldInfo.getEntity(agentID);
            if (agent == null || agent.getStandardURN() != StandardEntityURN.FIRE_BRIGADE) {
                System.err.println("[FireCommandPicker] ⚠️ 无效的智能体: " + agentID);
                continue;
            }

            StandardEntity target = this.worldInfo.getEntity(targetID);
            if (target == null) {
                System.err.println("[FireCommandPicker] ⚠️ 目标不存在: " + targetID);
                continue;
            }

            if (target instanceof Human) {
                CommandFire command = new CommandFire(
                    true, 
                    agentID, 
                    targetID, 
                    CommandFire.ACTION_RESCUE
                );
                this.messages.add(command);
                rescueCount++;
                //System.err.println("[FireCommandPicker]  生成救援命令: 消防车=" + agentID + " 目标人员=" + targetID);
            } else if (target instanceof Building) {
                Building building = (Building) target;
                if (building.isOnFire()) {
                    CommandFire command = new CommandFire(
                        true, 
                        agentID, 
                        targetID, 
                        CommandFire.ACTION_EXTINGUISH
                    );
                    this.messages.add(command);
                    extinguishCount++;
                    //System.err.println("[FireCommandPicker] 🔥 生成灭火命令: 消防车=" + agentID + " 目标建筑=" + targetID);
                } else {
                    CommandScout command = new CommandScout(
                        true, 
                        agentID, 
                        targetID, 
                        this.scoutDistance
                    );
                    this.messages.add(command);
                    scoutCount++;
                    //System.err.println("[FireCommandPicker] 🔍 生成侦察命令: 消防车=" + agentID + " 目标区域=" + targetID);
                }
            } else if (target instanceof Area) {
                CommandScout command = new CommandScout(
                    true, 
                    agentID, 
                    targetID, 
                    this.scoutDistance
                );
                this.messages.add(command);
                scoutCount++;
                //System.err.println("[FireCommandPicker] 🔍 生成侦察命令: 消防车=" + agentID + " 目标区域=" + targetID);
            } else {
                //System.err.println("[FireCommandPicker] ⚠️ 未知目标类型: " + target.getClass().getSimpleName());
            }
        }

        //System.err.println("[FireCommandPicker] 生成命令完成: 救援=" + rescueCount + 
                           //", 灭火=" + extinguishCount + ", 侦察=" + scoutCount);
        return this;
    }

    @Override
    public Collection<CommunicationMessage> getResult() {
        //System.err.println("[FireCommandPicker] getResult() 返回消息数: " + this.messages.size());
        return this.messages;
    }
}