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
    private boolean reportSent;

    private PathPlanning pathPlanning;
    private ExtAction actionFire;
    private ExtAction actionExtMove;

    public FireCommandExecutor(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                                ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.commandType = ACTION_UNKNOWN;
        this.reportSent = false;

        switch (scenarioInfo.getMode()) {
            case PRECOMPUTATION_PHASE:
            case PRECOMPUTED:
            case NON_PRECOMPUTE:
                this.pathPlanning = moduleManager.getModule(
                    "FireCommandExecutor.PathPlanning",
                    "ZCWL_2026.module.algorithm.PathPlanning");
                this.actionFire = moduleManager.getExtAction(
                    "FireCommandExecutor.ActionFire",
                    "ZCWL_2026.extraction.FireExtAction");
                this.actionExtMove = moduleManager.getExtAction(
                    "FireCommandExecutor.ActionExtMove",
                    "ZCWL_2026.extraction.ActionExtMove");
                break;
        }
        //System.err.println("[消防车执行器] ID:" + ai.getID() + " 已加载");
    }

    @Override
    public CommandExecutor<CommandFire> setCommand(CommandFire command) {
        EntityID agentID = this.agentInfo.getID();
        if (command.isToIDDefined() && Objects.requireNonNull(command.getToID()).getValue() == agentID.getValue()) {
            this.commandType = command.getAction();
            this.target = command.getTargetID();
            this.commanderID = command.getSenderID();
            this.reportSent = false;

            String actionName = getActionName(this.commandType);
           // System.err.println("[消防车执行器] 🚒 ID:" + agentID + " 收到命令: " + actionName + " 目标=" + this.target);
        } else {
            //System.err.println("[消防车执行器] ⚠️ ID:" + agentID + " 收到非本车命令，忽略");
        }
        return this;
    }

    @Override
    public CommandExecutor<CommandFire> updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() >= 2) return this;

        if (this.pathPlanning != null) this.pathPlanning.updateInfo(messageManager);
        if (this.actionFire != null) this.actionFire.updateInfo(messageManager);
        if (this.actionExtMove != null) this.actionExtMove.updateInfo(messageManager);

        if (!reportSent && this.commandType != ACTION_UNKNOWN && this.target != null) {
            if (isCommandCompleted()) {
                messageManager.addMessage(new MessageReport(true, true, false, this.commanderID));
                reportSent = true;
                //System.err.println("[消防车执行器] ✅ 完成任务: " + this.target);
                this.commandType = ACTION_UNKNOWN;
                this.target = null;
                this.commanderID = null;
            }
        }
        return this;
    }

    @Override
    public CommandExecutor<CommandFire> calc() {
        this.result = null;

        if (this.commandType == ACTION_UNKNOWN) {
            //System.err.println("[消防车执行器] 当前无命令");
            return this;
        }

        //System.err.println("[消防车执行器] 执行命令: " + getActionName(this.commandType) + " 目标=" + this.target);

        switch (this.commandType) {
            case ACTION_REST:
                this.result = handleRest();
                break;
            case ACTION_MOVE:
                if (this.target != null) {
                    this.result = this.actionExtMove.setTarget(this.target).calc().getAction();
                }
                break;
            case ACTION_RESCUE:
            case ACTION_EXTINGUISH:
                if (this.target != null) {
                    this.result = this.actionFire.setTarget(this.target).calc().getAction();
                }
                break;
            case ACTION_AUTONOMY:
                if (this.target != null) {
                    this.result = this.actionFire.setTarget(this.target).calc().getAction();
                }
                break;
            default:
                break;
        }

        if (this.result == null) {
            //System.err.println("[消防车执行器] ⚠️ 命令执行失败，未生成动作（目标可能无效或路径不通）");
        } else {
            //System.err.println("[消防车执行器] 生成动作: " + this.result.getClass().getSimpleName());
        }

        return this;
    }

    private Action handleRest() {
        EntityID position = this.agentInfo.getPosition();
        if (this.target != null) {
            if (position.getValue() != this.target.getValue()) {
                List<EntityID> path = getPath(position, this.target);
                if (path != null && !path.isEmpty()) {
                    return new ActionMove(path);
                }
            }
            return new ActionRest();
        }
        Collection<EntityID> refuges = this.worldInfo.getEntityIDsOfType(REFUGE);
        if (refuges.contains(position)) {
            return new ActionRest();
        }
        List<EntityID> path = getPath(position, refuges);
        if (path != null && !path.isEmpty()) {
            return new ActionMove(path);
        }
        return new ActionRest();
    }

    private List<EntityID> getPath(EntityID from, EntityID to) {
        if (this.pathPlanning == null) return null;
        return this.pathPlanning.getResult(from, to);
    }

    private List<EntityID> getPath(EntityID from, Collection<EntityID> targets) {
        if (this.pathPlanning == null) return null;
        this.pathPlanning.setFrom(from);
        this.pathPlanning.setDestination(targets);
        return this.pathPlanning.calc().getResult();
    }

    private boolean isCommandCompleted() {
        switch (this.commandType) {
            case ACTION_REST:
                if (this.target == null) {
                    Collection<EntityID> refuges = this.worldInfo.getEntityIDsOfType(REFUGE);
                    return refuges.contains(this.agentInfo.getPosition());
                }
                return this.agentInfo.getPosition().getValue() == this.target.getValue();
            case ACTION_MOVE:
                return this.target == null || this.agentInfo.getPosition().getValue() == this.target.getValue();
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
                    Building building = (Building) entity;
                    return !building.isOnFire();
                }
                return true;
            case ACTION_AUTONOMY:
                if (this.target == null) return false;
                StandardEntity targetEntity = this.worldInfo.getEntity(this.target);
                if (targetEntity instanceof Human) {
                    Human humanTarget = (Human) targetEntity;
                    return (humanTarget.isHPDefined() && humanTarget.getHP() == 0) ||
                           (humanTarget.isBuriednessDefined() && humanTarget.getBuriedness() == 0);
                } else if (targetEntity instanceof Building) {
                    Building building = (Building) targetEntity;
                    return !building.isOnFire();
                }
                return true;
            default:
                return true;
        }
    }

    private String getActionName(int action) {
        switch (action) {
            case ACTION_REST: return "休息";
            case ACTION_MOVE: return "移动";
            case ACTION_RESCUE: return "救援";
            case ACTION_EXTINGUISH: return "灭火";
            case ACTION_AUTONOMY: return "自主";
            default: return "未知(" + action + ")";
        }
    }

    // 以下方法保留以兼容旧接口
    @Override
    public CommandExecutor<CommandFire> precompute(PrecomputeData pd) { return this; }
    @Override
    public CommandExecutor<CommandFire> resume(PrecomputeData pd) { return this; }
    @Override
    public CommandExecutor<CommandFire> preparate() { return this; }
    public List<EntityID> getResult() { return null; }
}