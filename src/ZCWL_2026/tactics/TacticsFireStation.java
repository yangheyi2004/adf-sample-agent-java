package ZCWL_2026.tactics;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.debug.WorldViewLauncher;
import adf.core.component.centralized.CommandPicker;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.complex.TargetAllocator;
import rescuecore2.worldmodel.EntityID;

import java.util.Map;

public class TacticsFireStation extends adf.core.component.tactics.TacticsFireStation {
    private TargetAllocator allocator;
    private CommandPicker picker;
    private Boolean isVisualDebug;

    @Override
    public void initialize(AgentInfo agentInfo, WorldInfo worldInfo, ScenarioInfo scenarioInfo,
                          ModuleManager moduleManager, MessageManager messageManager, DevelopData debugData) {
        messageManager.setChannelSubscriber(moduleManager.getChannelSubscriber(
            "MessageManager.CenterChannelSubscriber",
            "ZCWL_2026.communication.ChannelSubscriber"));
        messageManager.setMessageCoordinator(moduleManager.getMessageCoordinator(
            "MessageManager.CenterMessageCoordinator",
            "ZCWL_2026.communication.MessageCoordinator"));

        switch (scenarioInfo.getMode()) {
            case PRECOMPUTATION_PHASE:
            case PRECOMPUTED:
            case NON_PRECOMPUTE:
                this.allocator = moduleManager.getModule(
                    "TacticsFireStation.TargetAllocator",
                    "ZCWL_2026.module.complex.FireTargetAllocator");
                this.picker = moduleManager.getCommandPicker(
                    "TacticsFireStation.CommandPicker",
                    "ZCWL_2026.centralized.FireCommandPicker");
                break;
        }
        registerModule(this.allocator);
        registerModule(this.picker);

        this.isVisualDebug = (scenarioInfo.isDebugMode()
                && moduleManager.getModuleConfig().getBooleanValue("VisualDebug", false));
    }

    @Override
    public void think(AgentInfo agentInfo, WorldInfo worldInfo, ScenarioInfo scenarioInfo,
                     ModuleManager moduleManager, MessageManager messageManager, DevelopData debugData) {
        modulesUpdateInfo(messageManager);

        if (isVisualDebug) {
            WorldViewLauncher.getInstance().showTimeStep(agentInfo, worldInfo, scenarioInfo);
        }

        Map<EntityID, EntityID> allocatorResult = this.allocator.calc().getResult();
        for (CommunicationMessage message : this.picker.setAllocatorResult(allocatorResult).calc().getResult()) {
            messageManager.addMessage(message);
        }
    }

    @Override
    public void resume(AgentInfo agentInfo, WorldInfo worldInfo, ScenarioInfo scenarioInfo,
                      ModuleManager moduleManager, PrecomputeData precomputeData, DevelopData debugData) {
        modulesResume(precomputeData);

        if (isVisualDebug) {
            WorldViewLauncher.getInstance().showTimeStep(agentInfo, worldInfo, scenarioInfo);
        }
    }

    @Override
    public void preparate(AgentInfo agentInfo, WorldInfo worldInfo, ScenarioInfo scenarioInfo,
                         ModuleManager moduleManager, DevelopData debugData) {
        modulesPreparate();

        if (isVisualDebug) {
            WorldViewLauncher.getInstance().showTimeStep(agentInfo, worldInfo, scenarioInfo);
        }
    }
}

