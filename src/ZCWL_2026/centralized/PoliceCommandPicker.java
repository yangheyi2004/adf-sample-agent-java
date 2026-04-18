package ZCWL_2026.centralized;

import adf.core.component.centralized.CommandPicker;
import adf.core.component.communication.CommunicationMessage;
import adf.core.agent.communication.standard.bundle.centralized.CommandPolice;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import rescuecore2.worldmodel.EntityID;

import java.util.*;

public class PoliceCommandPicker extends adf.core.component.centralized.CommandPicker {

    private Collection<CommunicationMessage> messages;
    private Map<EntityID, EntityID> allocationData;

    public PoliceCommandPicker(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                               ModuleManager mm, DevelopData dd) {
        super(ai, wi, si, mm, dd);
        this.messages = new ArrayList<>();
        //System.err.println("[PoliceCommandPicker] 已加载（仅清理命令）");
    }

    @Override
    public CommandPicker setAllocatorResult(Map<EntityID, EntityID> allocationData) {
        this.allocationData = allocationData;
        return this;
    }

    @Override
    public CommandPicker calc() {
        this.messages.clear();
        if (allocationData == null || allocationData.isEmpty()) {
            return this;
        }

        for (Map.Entry<EntityID, EntityID> entry : allocationData.entrySet()) {
            EntityID policeId = entry.getKey();
            EntityID target = entry.getValue();

            // 只发送清理命令，不再发送自主命令
            if (target != null) {
                CommandPolice command = new CommandPolice(true, policeId, target, CommandPolice.ACTION_CLEAR);
                this.messages.add(command);
                //System.err.printf("[PoliceCommandPicker] 警察 %s → 清理命令 道路=%s%n", policeId, target);
            }
            // 如果 target 为 null，则忽略（不再发送自主命令）
        }
        return this;
    }

    @Override
    public Collection<CommunicationMessage> getResult() {
        return this.messages;
    }
}