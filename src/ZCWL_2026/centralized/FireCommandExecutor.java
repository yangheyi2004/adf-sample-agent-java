package ZCWL_2026.centralized;

import adf.core.component.communication.CommunicationMessage;
import adf.core.component.centralized.CommandExecutor;
import adf.core.agent.action.Action;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.action.common.ActionRest;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.centralized.CommandFire;
import adf.core.agent.communication.standard.bundle.centralized.MessageReport;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.extaction.ExtAction;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.util.*;
import static rescuecore2.standard.entities.StandardEntityURN.*;

public class FireCommandExecutor extends adf.core.component.centralized.CommandExecutor<CommandFire> {

    private static final int ACTION_UNKNOWN = -1;
    private static final int ACTION_REST = CommandFire.ACTION_REST;
    private static final int ACTION_MOVE = CommandFire.ACTION_MOVE;
    private static final int ACTION_RESCUE = CommandFire.ACTION_RESCUE;
    private static final int ACTION_EXTINGUISH = CommandFire.ACTION_EXTINGUISH;
    private static final int ACTION_AUTONOMY = CommandFire.ACTION_AUTONOMY;

    private int commandType;
    private EntityID target;
    private EntityID commanderID;
    private boolean reportSent = false;

    private PathPlanning pathPlanning;
    private ExtAction actionFireFighting;
    private ExtAction actionFireRescue;
    private ExtAction actionExtMove;

    public FireCommandExecutor(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                                ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.commandType = ACTION_UNKNOWN;
        
        /*System.err.println("╔══════════════════════════════════════════════════════════════╗");
        System.err.println("║  [ZCWL_2026] 消防车执行器已加载！                            ║");
        System.err.println("║  ID: " + ai.getID() + "                                         ║");
        System.err.println("╚══════════════════════════════════════════════════════════════╝");*/

        switch (scenarioInfo.getMode()) {
            case PRECOMPUTATION_PHASE:
            case PRECOMPUTED:
            case NON_PRECOMPUTE:
                this.pathPlanning = moduleManager.getModule(
                    "FireCommandExecutor.PathPlanning",
                    "ZCWL_2026.module.algorithm.PathPlanning");
                this.actionFireFighting = moduleManager.getExtAction(
                    "FireCommandExecutor.ActionFireFighting",
                    "ZCWL_2026.extraction.ActionFireFighting");
                this.actionFireRescue = moduleManager.getExtAction(
                    "FireCommandExecutor.ActionFireRescue",
                    "ZCWL_2026.extraction.ActionFireRescue");
                this.actionExtMove = moduleManager.getExtAction(
                    "FireCommandExecutor.ActionExtMove",
                    "ZCWL_2026.extraction.ActionExtMove");
                break;
        }
    }

    @Override
    public CommandExecutor<CommandFire> setCommand(CommandFire command) {
        EntityID agentID = this.agentInfo.getID();
        if (command.isToIDDefined() && Objects.requireNonNull(command.getToID()).getValue() == agentID.getValue()) {
            this.commandType = command.getAction();
            this.target = command.getTargetID();
            this.commanderID = command.getSenderID();
            this.reportSent = false;
            
            String actionName = "";
            if (this.commandType == ACTION_REST) actionName = "休息";
            else if (this.commandType == ACTION_MOVE) actionName = "移动";
            else if (this.commandType == ACTION_RESCUE) actionName = "救援";
            else if (this.commandType == ACTION_EXTINGUISH) actionName = "灭火";
            else if (this.commandType == ACTION_AUTONOMY) actionName = "自主";
            
            System.err.println("╔══════════════════════════════════════════════════════════════╗");
            System.err.println("║  [ZCWL_2026] 🚒 消防车 ID:" + agentID + " 收到命令！           ║");
            System.err.println("║  命令类型: " + actionName + " (" + this.commandType + ")        ║");
            System.err.println("║  目标: " + this.target + "                                    ║");
            System.err.println("╚══════════════════════════════════════════════════════════════╝");
        }
        return this;
    }

    @Override
    public CommandExecutor<CommandFire> updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() >= 2) return this;

        this.pathPlanning.updateInfo(messageManager);
        this.actionFireFighting.updateInfo(messageManager);
        this.actionFireRescue.updateInfo(messageManager);
        this.actionExtMove.updateInfo(messageManager);

        if (isCommandCompleted() && !reportSent) {
            if (this.commandType != ACTION_UNKNOWN && this.target != null) {
                messageManager.addMessage(new MessageReport(true, true, false, this.commanderID));
                reportSent = true;
                System.err.println("╔══════════════════════════════════════════════════════════════╗");
                System.err.println("║  [ZCWL_2026] ✅ 消防车 ID:" + this.agentInfo.getID() + " 完成任务！   ║");
                System.err.println("║  目标: " + this.target + "                                    ║");
                System.err.println("╚══════════════════════════════════════════════════════════════╝");
                this.commandType = ACTION_UNKNOWN;
                this.target = null;
                this.commanderID = null;
            }
        }
        return this;
    }

    @Override
    public CommandExecutor<CommandFire> precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        if (this.getCountPrecompute() >= 2) return this;
        this.pathPlanning.precompute(precomputeData);
        this.actionFireFighting.precompute(precomputeData);
        this.actionFireRescue.precompute(precomputeData);
        this.actionExtMove.precompute(precomputeData);
        return this;
    }

    @Override
    public CommandExecutor<CommandFire> resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (this.getCountResume() >= 2) return this;
        this.pathPlanning.resume(precomputeData);
        this.actionFireFighting.resume(precomputeData);
        this.actionFireRescue.resume(precomputeData);
        this.actionExtMove.resume(precomputeData);
        return this;
    }

    @Override
    public CommandExecutor<CommandFire> preparate() {
        super.preparate();
        if (this.getCountPreparate() >= 2) return this;
        this.pathPlanning.preparate();
        this.actionFireFighting.preparate();
        this.actionFireRescue.preparate();
        this.actionExtMove.preparate();
        return this;
    }

    @Override
    public CommandExecutor<CommandFire> calc() {
        this.result = null;

        switch (this.commandType) {
            case ACTION_REST:
                this.result = handleRest();
                return this;

            case ACTION_MOVE:
                if (this.target != null) {
                    this.result = this.actionExtMove.setTarget(this.target).calc().getAction();
                }
                return this;

            case ACTION_RESCUE:
                if (this.target != null) {
                    this.result = this.actionFireRescue.setTarget(this.target).calc().getAction();
                }
                return this;

            case ACTION_EXTINGUISH:
                if (this.target != null) {
                    this.result = this.actionFireFighting.setTarget(this.target).calc().getAction();
                }
                return this;

            case ACTION_AUTONOMY:
                return this;

            default:
                return this;
        }
    }

    private Action handleRest() {
        EntityID position = this.agentInfo.getPosition();
        
        if (this.target == null) {
            Collection<EntityID> refuges = this.worldInfo.getEntityIDsOfType(REFUGE);
            if (refuges.contains(position)) {
                return new ActionRest();
            } else {
                this.pathPlanning.setFrom(position);
                this.pathPlanning.setDestination(refuges);
                List<EntityID> path = this.pathPlanning.calc().getResult();
                if (path != null && path.size() > 0) {
                    return new ActionMove(path);
                }
                return new ActionRest();
            }
        }
        
        if (position.getValue() != this.target.getValue()) {
            List<EntityID> path = this.pathPlanning.getResult(position, this.target);
            if (path != null && path.size() > 0) {
                return new ActionMove(path);
            }
        }
        return new ActionRest();
    }

    private boolean isCommandCompleted() {
        switch (this.commandType) {
            case ACTION_REST:
                if (this.target == null) {
                    return true;
                }
                return false;

            case ACTION_MOVE:
                return this.target == null || 
                       this.agentInfo.getPosition().getValue() == this.target.getValue();

            case ACTION_RESCUE:
                if (this.target == null) return true;
                Human human = (Human) this.worldInfo.getEntity(this.target);
                if (human == null) return true;
                return (human.isHPDefined() && human.getHP() == 0) ||
                       (human.isBuriednessDefined() && human.getBuriedness() == 0);

            case ACTION_EXTINGUISH:
                if (this.target == null) return true;
                StandardEntity entity = this.worldInfo.getEntity(this.target);
                if (entity instanceof Building) {
                    return !((Building) entity).isOnFire();
                }
                return true;

            default:
                return true;
        }
    }

    public List<EntityID> getResult() {
        return null;
    }
}