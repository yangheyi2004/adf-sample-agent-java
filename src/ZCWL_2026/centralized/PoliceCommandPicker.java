package ZCWL_2026.centralized;

import adf.core.component.centralized.CommandPicker;
import adf.core.component.communication.CommunicationMessage;
import adf.core.agent.communication.standard.bundle.centralized.CommandPolice;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

public class PoliceCommandPicker extends adf.core.component.centralized.CommandPicker {

    private Collection<CommunicationMessage> messages;
    private Map<EntityID, EntityID> allocationData;

    public PoliceCommandPicker(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                                ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.messages = new ArrayList<>();
        this.allocationData = null;
        
        System.err.println("[ZCWL_2026] PoliceCommandPicker 已加载");
    }

    @Override
    public CommandPicker setAllocatorResult(Map<EntityID, EntityID> allocationData) {
        this.allocationData = allocationData;
        System.err.println("[ZCWL_2026] PoliceCommandPicker 收到分配结果，任务数: " + 
                           (allocationData != null ? allocationData.size() : 0));
        return this;
    }

    @Override
    public CommandPicker calc() {
        this.messages.clear();
        if (this.allocationData == null) {
            System.err.println("[ZCWL_2026] PoliceCommandPicker 没有分配结果");
            return this;
        }
        
        System.err.println("[ZCWL_2026] PoliceCommandPicker 开始生成命令，分配数: " + this.allocationData.size());
        
        for (Map.Entry<EntityID, EntityID> entry : this.allocationData.entrySet()) {
            EntityID agentID = entry.getKey();
            EntityID targetID = entry.getValue();
            
            System.err.println("[ZCWL_2026] PoliceCommandPicker 处理: 警察=" + agentID + ", 目标=" + targetID);
            
            StandardEntity agent = this.worldInfo.getEntity(agentID);
            if (agent != null && agent.getStandardURN() == StandardEntityURN.POLICE_FORCE) {
                StandardEntity target = this.worldInfo.getEntity(targetID);
                if (target != null && target instanceof Area) {
                    CommandPolice command = new CommandPolice(
                            true,
                            agentID,
                            target.getID(),
                            CommandPolice.ACTION_AUTONOMY
                    );
                    this.messages.add(command);
                    System.err.println("[ZCWL_2026] PoliceCommandPicker 生成命令: 警察=" + agentID + 
                                       ", 目标=" + targetID);
                } else {
                    System.err.println("[ZCWL_2026] PoliceCommandPicker 警告: 目标无效或不是Area");
                }
            } else {
                System.err.println("[ZCWL_2026] PoliceCommandPicker 警告: 警察不存在或类型错误");
            }
        }
        
        System.err.println("[ZCWL_2026] PoliceCommandPicker 生成命令完成，消息数: " + this.messages.size());
        return this;
    }

    @Override
    public Collection<CommunicationMessage> getResult() {
        return this.messages;
    }
}