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

/**
 * 消防车命令执行器 - 整合版
 * 
 * 功能：
 * 1. 接收中心指挥的命令（移动、清理、休息、自主）
 * 2. 使用统一的 FireExtAction 模块处理灭火和救援
 */
public class FireCommandExecutor extends adf.core.component.centralized.CommandExecutor<CommandFire> {

    // ==================== 命令类型常量 ====================
    private static final int ACTION_UNKNOWN = -1;
    private static final int ACTION_REST = CommandFire.ACTION_REST;
    private static final int ACTION_MOVE = CommandFire.ACTION_MOVE;
    private static final int ACTION_RESCUE = CommandFire.ACTION_RESCUE;
    private static final int ACTION_EXTINGUISH = CommandFire.ACTION_EXTINGUISH;
    private static final int ACTION_AUTONOMY = CommandFire.ACTION_AUTONOMY;

    // ==================== 成员变量 ====================
    private int commandType;
    private EntityID target;
    private EntityID commanderID;
    private boolean reportSent;

    // 模块依赖
    private PathPlanning pathPlanning;
    private ExtAction actionFire;      // 整合后的统一动作模块（灭火+救援）
    private ExtAction actionExtMove;   // 移动动作

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
                // 整合后的统一动作模块
                this.actionFire = moduleManager.getExtAction(
                    "FireCommandExecutor.ActionFire",
                    "ZCWL_2026.extraction.FireExtAction");
                this.actionExtMove = moduleManager.getExtAction(
                    "FireCommandExecutor.ActionExtMove",
                    "ZCWL_2026.extraction.ActionExtMove");
                break;
        }
        
        //System.err.println("[消防车执行器] ID:" + ai.getID() + " 已加载（整合版）");
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
            
            System.err.println("╔══════════════════════════════════════════════════════════════╗");
            System.err.println("║  [消防车执行器] 🚒 消防车 ID:" + agentID + " 收到命令！         ║");
            System.err.println("║  命令类型: " + actionName + " (" + this.commandType + ")");
            System.err.println("║  目标: " + (this.target == null ? "无" : this.target));
            System.err.println("╚══════════════════════════════════════════════════════════════╝");
        }
        return this;
    }

    @Override
    public CommandExecutor<CommandFire> updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() >= 2) return this;

        // 更新子模块
        if (this.pathPlanning != null) {
            this.pathPlanning.updateInfo(messageManager);
        }
        if (this.actionFire != null) {
            this.actionFire.updateInfo(messageManager);
        }
        if (this.actionExtMove != null) {
            this.actionExtMove.updateInfo(messageManager);
        }

        // 检查命令是否完成
        if (!reportSent && isCommandCompleted()) {
            if (this.commandType != ACTION_UNKNOWN && this.target != null) {
                messageManager.addMessage(new MessageReport(true, true, false, this.commanderID));
                reportSent = true;
                
                System.err.println("[消防车执行器] ✅ 完成任务: " + this.target);
                
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
        if (this.pathPlanning != null) this.pathPlanning.precompute(precomputeData);
        if (this.actionFire != null) this.actionFire.precompute(precomputeData);
        if (this.actionExtMove != null) this.actionExtMove.precompute(precomputeData);
        return this;
    }

    @Override
    public CommandExecutor<CommandFire> resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (this.getCountResume() >= 2) return this;
        if (this.pathPlanning != null) this.pathPlanning.resume(precomputeData);
        if (this.actionFire != null) this.actionFire.resume(precomputeData);
        if (this.actionExtMove != null) this.actionExtMove.resume(precomputeData);
        return this;
    }

    @Override
    public CommandExecutor<CommandFire> preparate() {
        super.preparate();
        if (this.getCountPreparate() >= 2) return this;
        if (this.pathPlanning != null) this.pathPlanning.preparate();
        if (this.actionFire != null) this.actionFire.preparate();
        if (this.actionExtMove != null) this.actionExtMove.preparate();
        return this;
    }

    @Override
    public CommandExecutor<CommandFire> calc() {
        this.result = null;

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
                // 统一使用 actionFire 模块，它会根据 target 类型自动判断是灭火还是救援
                if (this.target != null) {
                    this.result = this.actionFire.setTarget(this.target).calc().getAction();
                }
                break;

            case ACTION_AUTONOMY:
                // 自主模式：使用 actionFire 模块处理
                if (this.target != null) {
                    this.result = this.actionFire.setTarget(this.target).calc().getAction();
                }
                break;

            default:
                // 未知命令，不处理
                break;
        }

        return this;
    }

    /**
     * 处理休息命令
     */
    private Action handleRest() {
        EntityID position = this.agentInfo.getPosition();
        
        // 如果有指定目标位置
        if (this.target != null) {
            if (position.getValue() != this.target.getValue()) {
                List<EntityID> path = getPath(position, this.target);
                if (path != null && !path.isEmpty()) {
                    return new ActionMove(path);
                }
            }
            return new ActionRest();
        }
        
        // 否则前往避难所
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

    /**
     * 获取路径
     */
    private List<EntityID> getPath(EntityID from, EntityID to) {
        if (this.pathPlanning == null) return null;
        return this.pathPlanning.getResult(from, to);
    }
    
    /**
     * 获取到目标集合的路径
     */
    private List<EntityID> getPath(EntityID from, Collection<EntityID> targets) {
        if (this.pathPlanning == null) return null;
        this.pathPlanning.setFrom(from);
        this.pathPlanning.setDestination(targets);
        return this.pathPlanning.calc().getResult();
    }

    /**
     * 判断命令是否完成
     */
    private boolean isCommandCompleted() {
        switch (this.commandType) {
            case ACTION_REST:
                // 休息命令：到达避难所即可完成
                if (this.target == null) {
                    Collection<EntityID> refuges = this.worldInfo.getEntityIDsOfType(REFUGE);
                    return refuges.contains(this.agentInfo.getPosition());
                }
                return this.agentInfo.getPosition().getValue() == this.target.getValue();

            case ACTION_MOVE:
                return this.target == null || 
                       this.agentInfo.getPosition().getValue() == this.target.getValue();

            case ACTION_RESCUE:
                if (this.target == null) return true;
                Human human = (Human) this.worldInfo.getEntity(this.target);
                if (human == null) return true;
                // 救援完成条件：已死亡 或 已被挖出（埋压度为0）
                return (human.isHPDefined() && human.getHP() == 0) ||
                       (human.isBuriednessDefined() && human.getBuriedness() == 0);

            case ACTION_EXTINGUISH:
                if (this.target == null) return true;
                StandardEntity entity = this.worldInfo.getEntity(this.target);
                if (entity instanceof Building) {
                    Building building = (Building) entity;
                    // 灭火完成条件：建筑不再着火
                    return !building.isOnFire();
                }
                return true;

            case ACTION_AUTONOMY:
                // 自主模式：当前任务完成即可
                if (this.target == null) return false;
                // 检查目标类型
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

    /**
     * 获取命令名称
     */
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

    public List<EntityID> getResult() {
        return null;
    }
}