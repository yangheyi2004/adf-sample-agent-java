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
    }

    @Override
    public CommandPicker setAllocatorResult(Map<EntityID, EntityID> allocationData) {
        this.allocationData = allocationData;
        return this;
    }

    @Override
    public CommandPicker calc() {
        this.messages.clear();
        if (this.allocationData == null) {
            return this;
        }
        
        for (Map.Entry<EntityID, EntityID> entry : this.allocationData.entrySet()) {
            EntityID agentID = entry.getKey();
            EntityID targetID = entry.getValue();
            
            StandardEntity agent = this.worldInfo.getEntity(agentID);
            if (agent == null || agent.getStandardURN() != StandardEntityURN.FIRE_BRIGADE) {
                continue;
            }
            
            StandardEntity target = this.worldInfo.getEntity(targetID);
            if (target == null) {
                continue;
            }
            
            if (target instanceof Human) {
                // 人员目标 → 救援命令
                CommandFire command = new CommandFire(
                    true, 
                    agentID, 
                    targetID, 
                    CommandFire.ACTION_RESCUE  // 明确指定救援动作
                );
                this.messages.add(command);
                System.err.println("[FireCommandPicker] 🚒 生成救援命令: 消防车=" + agentID + " 目标人员=" + targetID);
                
            } else if (target instanceof Building) {
                Building building = (Building) target;
                if (building.isOnFire()) {
                    // 着火建筑 → 灭火命令
                    CommandFire command = new CommandFire(
                        true, 
                        agentID, 
                        targetID, 
                        CommandFire.ACTION_EXTINGUISH
                    );
                    this.messages.add(command);
                    System.err.println("[FireCommandPicker] 🔥 生成灭火命令: 消防车=" + agentID + " 目标建筑=" + targetID);
                } else {
                    // 非着火建筑 → 侦察命令
                    CommandScout command = new CommandScout(
                        true, 
                        agentID, 
                        targetID, 
                        this.scoutDistance
                    );
                    this.messages.add(command);
                    System.err.println("[FireCommandPicker] 🔍 生成侦察命令: 消防车=" + agentID + " 目标区域=" + targetID);
                }
            } else if (target instanceof Area) {
                // 其他区域 → 侦察命令
                CommandScout command = new CommandScout(
                    true, 
                    agentID, 
                    targetID, 
                    this.scoutDistance
                );
                this.messages.add(command);
                System.err.println("[FireCommandPicker] 🔍 生成侦察命令: 消防车=" + agentID + " 目标区域=" + targetID);
            }
        }
        return this;
    }

    @Override
    public Collection<CommunicationMessage> getResult() {
        return this.messages;
    }
}