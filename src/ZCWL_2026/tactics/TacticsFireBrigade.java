package ZCWL_2026.tactics;

import adf.core.agent.action.Action;
import adf.core.agent.action.fire.ActionExtinguish;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.action.common.ActionRest;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.centralized.CommandFire;
import adf.core.agent.communication.standard.bundle.centralized.CommandScout;
import adf.core.agent.communication.standard.bundle.information.MessageFireBrigade;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.debug.WorldViewLauncher;
import adf.core.component.centralized.CommandExecutor;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.extaction.ExtAction;
import adf.core.component.module.complex.HumanDetector;
import adf.core.component.module.complex.Search;
import ZCWL_2026.tactics.utils.MessageTool;
import rescuecore2.standard.entities.FireBrigade;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

import java.util.List;
import java.util.Objects;

public class TacticsFireBrigade extends adf.core.component.tactics.TacticsFireBrigade {  // 修改1：类名改为 TacticsFireBrigade，并使用完整包名
    private HumanDetector humanDetector;
    private Search search;

    private ExtAction actionFireExtinguish;
    private ExtAction actionExtMove;

    private CommandExecutor<CommandFire> commandExecutorFire;
    private CommandExecutor<CommandScout> commandExecutorScout;

    private MessageTool messageTool;

    private CommunicationMessage recentCommand;
    private Boolean isVisualDebug;

    @Override
    public void initialize(AgentInfo agentInfo, WorldInfo worldInfo, ScenarioInfo scenarioInfo,
                          ModuleManager moduleManager, MessageManager messageManager, DevelopData developData) {
        messageManager.setChannelSubscriber(moduleManager.getChannelSubscriber(
            "MessageManager.PlatoonChannelSubscriber",
            "ZCWL_2026.communication.ChannelSubscriber"));
        messageManager.setMessageCoordinator(moduleManager.getMessageCoordinator(
            "MessageManager.PlatoonMessageCoordinator",
            "ZCWL_2026.communication.MessageCoordinator"));

        worldInfo.indexClass(
                StandardEntityURN.CIVILIAN,
                StandardEntityURN.FIRE_BRIGADE,
                StandardEntityURN.POLICE_FORCE,
                StandardEntityURN.AMBULANCE_TEAM,
                StandardEntityURN.ROAD,
                StandardEntityURN.HYDRANT,
                StandardEntityURN.BUILDING,
                StandardEntityURN.REFUGE,
                StandardEntityURN.GAS_STATION,
                StandardEntityURN.AMBULANCE_CENTRE,
                StandardEntityURN.FIRE_STATION,
                StandardEntityURN.POLICE_OFFICE
        );

        this.messageTool = new MessageTool(scenarioInfo, developData);

        this.isVisualDebug = (scenarioInfo.isDebugMode()
                && moduleManager.getModuleConfig().getBooleanValue("VisualDebug", false));

        this.recentCommand = null;

        switch (scenarioInfo.getMode()) {
            case PRECOMPUTATION_PHASE:
            case PRECOMPUTED:
            case NON_PRECOMPUTE:
                this.humanDetector = moduleManager.getModule(
                    "TacticsFireBrigade.HumanDetector",
                    "ZCWL_2026.module.complex.HumanDetector");
                this.search = moduleManager.getModule(
                    "TacticsFireBrigade.Search",
                    "ZCWL_2026.module.complex.MySearch");  // 修改2：Search -> MySearch
                this.actionFireExtinguish = moduleManager.getExtAction(
                    "TacticsFireBrigade.ActionFireExtinguish",
                    "ZCWL_2026.extraction.FireExtAction");
                this.actionExtMove = moduleManager.getExtAction(
                    "TacticsFireBrigade.ActionExtMove",
                    "ZCWL_2026.extraction.FireExtAction");
                this.commandExecutorFire = moduleManager.getCommandExecutor(
                    "TacticsFireBrigade.CommandExecutorFire",
                    "ZCWL_2026.centralized.FireCommandExecutor");
                this.commandExecutorScout = moduleManager.getCommandExecutor(
                    "TacticsFireBrigade.CommandExecutorScout",
                    "ZCWL_2026.centralized.FireCommandExecutor");  // 修改3：CommandExecutorScout -> FireCommandExecutor
                break;
        }
        registerModule(this.humanDetector);
        registerModule(this.search);
        registerModule(this.actionFireExtinguish);
        registerModule(this.actionExtMove);
        registerModule(this.commandExecutorFire);
        registerModule(this.commandExecutorScout);
    }

    @Override
    public void precompute(AgentInfo agentInfo, WorldInfo worldInfo, ScenarioInfo scenarioInfo,
                          ModuleManager moduleManager, PrecomputeData precomputeData, DevelopData developData) {
        modulesPrecompute(precomputeData);
    }

    @Override
    public void resume(AgentInfo agentInfo, WorldInfo worldInfo, ScenarioInfo scenarioInfo,
                      ModuleManager moduleManager, PrecomputeData precomputeData, DevelopData developData) {
        modulesResume(precomputeData);

        if (isVisualDebug) {
            WorldViewLauncher.getInstance().showTimeStep(agentInfo, worldInfo, scenarioInfo);
        }
    }

    @Override
    public void preparate(AgentInfo agentInfo, WorldInfo worldInfo, ScenarioInfo scenarioInfo,
                         ModuleManager moduleManager, DevelopData developData) {
        modulesPreparate();

        if (isVisualDebug) {
            WorldViewLauncher.getInstance().showTimeStep(agentInfo, worldInfo, scenarioInfo);
        }
    }

    @Override
    public Action think(AgentInfo agentInfo, WorldInfo worldInfo, ScenarioInfo scenarioInfo,
                       ModuleManager moduleManager, MessageManager messageManager, DevelopData developData) {
        this.messageTool.reflectMessage(agentInfo, worldInfo, scenarioInfo, messageManager);
        this.messageTool.sendRequestMessages(agentInfo, worldInfo, scenarioInfo, messageManager);
        this.messageTool.sendInformationMessages(agentInfo, worldInfo, scenarioInfo, messageManager);

        modulesUpdateInfo(messageManager);

        if (isVisualDebug) {
            WorldViewLauncher.getInstance().showTimeStep(agentInfo, worldInfo, scenarioInfo);
        }

        FireBrigade agent = (FireBrigade) agentInfo.me();
        EntityID agentID = agentInfo.getID();

        for (CommunicationMessage message : messageManager.getReceivedMessageList(CommandScout.class)) {
            CommandScout command = (CommandScout) message;
            if (command.isToIDDefined() && Objects.requireNonNull(command.getToID()).getValue() == agentID.getValue()) {
                this.recentCommand = command;
                this.commandExecutorScout.setCommand(command);
            }
        }

        for (CommunicationMessage message : messageManager.getReceivedMessageList(CommandFire.class)) {
            CommandFire command = (CommandFire) message;
            if (command.isToIDDefined() && Objects.requireNonNull(command.getToID()).getValue() == agentID.getValue()) {
                this.recentCommand = command;
                this.commandExecutorFire.setCommand(command);
            }
        }

        if (this.recentCommand != null) {
            Action action = null;
            if (this.recentCommand.getClass() == CommandFire.class) {
                action = this.commandExecutorFire.calc().getAction();
            } else if (this.recentCommand.getClass() == CommandScout.class) {
                action = this.commandExecutorScout.calc().getAction();
            }
            if (action != null) {
                this.sendActionMessage(messageManager, agent, action);
                return action;
            }
        }

        EntityID target = this.humanDetector.calc().getTarget();
        Action action = this.actionFireExtinguish.setTarget(target).calc().getAction();
        if (action != null) {
            this.sendActionMessage(messageManager, agent, action);
            return action;
        }

        target = this.search.calc().getTarget();
        action = this.actionExtMove.setTarget(target).calc().getAction();
        if (action != null) {
            this.sendActionMessage(messageManager, agent, action);
            return action;
        }

        messageManager.addMessage(
                new MessageFireBrigade(true, agent, MessageFireBrigade.ACTION_REST, agent.getPosition())
        );
        return new ActionRest();
    }

    private void sendActionMessage(MessageManager messageManager, FireBrigade fireBrigade, Action action) {
        Class<? extends Action> actionClass = action.getClass();
        int actionIndex = -1;
        EntityID target = null;
        if (actionClass == ActionMove.class) {
            actionIndex = MessageFireBrigade.ACTION_MOVE;
            List<EntityID> path = ((ActionMove) action).getPath();
            if (path.size() > 0) {
                target = path.get(path.size() - 1);
            }
        } else if (actionClass == ActionExtinguish.class) {
            actionIndex = MessageFireBrigade.ACTION_EXTINGUISH;
            target = ((ActionExtinguish) action).getTarget();
        } else if (actionClass == ActionRest.class) {
            actionIndex = MessageFireBrigade.ACTION_REST;
            target = fireBrigade.getPosition();
        }
        if (actionIndex != -1) {
            messageManager.addMessage(
                    new MessageFireBrigade(true, fireBrigade, actionIndex, target)
            );
        }
    }
}
