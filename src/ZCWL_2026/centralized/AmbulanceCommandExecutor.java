package ZCWL_2026.centralized;

import adf.core.component.communication.CommunicationMessage;
import adf.core.component.centralized.CommandExecutor;
import adf.core.agent.action.Action;
import adf.core.agent.action.ambulance.ActionLoad;
import adf.core.agent.action.ambulance.ActionRescue;
import adf.core.agent.action.ambulance.ActionUnload;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.action.common.ActionRest;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.centralized.CommandAmbulance;
import adf.core.agent.communication.standard.bundle.centralized.MessageReport;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.extaction.ExtAction;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.worldmodel.EntityID;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

import static rescuecore2.standard.entities.StandardEntityURN.REFUGE;

public class AmbulanceCommandExecutor extends CommandExecutor {

    private static final int ACTION_UNKNOWN = -1;
    private static final int ACTION_REST = CommandAmbulance.ACTION_REST;
    private static final int ACTION_MOVE = CommandAmbulance.ACTION_MOVE;
    private static final int ACTION_RESCUE = CommandAmbulance.ACTION_RESCUE;
    private static final int ACTION_LOAD = CommandAmbulance.ACTION_LOAD;
    private static final int ACTION_UNLOAD = CommandAmbulance.ACTION_UNLOAD;
    private static final int ACTION_AUTONOMY = CommandAmbulance.ACTION_AUTONOMY;

    // ========== 距离检查常量 ==========
    private static final int MAX_LOAD_DISTANCE = 100;   // 最大装载距离
    private static final int MAX_STUCK_COUNT = 30;
    private static final int MAX_NO_PROGRESS = 20;

    private PathPlanning pathPlanning;
    private ExtAction actionTransport;
    private ExtAction actionExtMove;

    private int commandType;
    private EntityID target;
    private EntityID commanderID;
    private boolean reportSent;
    private boolean isForcedLoad;
    private int taskPriority;

    // 防卡死状态
    private EntityID lastPosition;
    private int stuckCounter;
    private EntityID lastTarget;
    private int noProgressCounter;
    
    // 已处理的平民缓存
    private java.util.Set<EntityID> processedVictims;

    public AmbulanceCommandExecutor(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                                     ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.commandType = ACTION_UNKNOWN;
        this.reportSent = false;
        this.isForcedLoad = false;
        this.taskPriority = 2;
        this.lastPosition = null;
        this.stuckCounter = 0;
        this.lastTarget = null;
        this.noProgressCounter = 0;
        this.processedVictims = new java.util.HashSet<>();

        switch (scenarioInfo.getMode()) {
            case PRECOMPUTATION_PHASE:
            case PRECOMPUTED:
            case NON_PRECOMPUTE:
                this.pathPlanning = moduleManager.getModule(
                    "AmbulanceCommandExecutor.PathPlanning",
                    "ZCWL_2026.module.algorithm.PathPlanning");
                this.actionTransport = moduleManager.getExtAction(
                    "AmbulanceCommandExecutor.ActionTransport",
                    "ZCWL_2026.extraction.ActionTransport");
                this.actionExtMove = moduleManager.getExtAction(
                    "AmbulanceCommandExecutor.ActionExtMove",
                    "ZCWL_2026.extraction.ActionExtMove");
                break;
        }
    }

    @Override
    public CommandExecutor setCommand(CommunicationMessage command) {
        if (command instanceof CommandAmbulance) {
            CommandAmbulance cmd = (CommandAmbulance) command;
            EntityID agentID = this.agentInfo.getID();
            if (cmd.isToIDDefined() && Objects.requireNonNull(cmd.getToID())
                    .getValue() == agentID.getValue()) {
                this.commandType = cmd.getAction();
                this.target = cmd.getTargetID();
                this.commanderID = cmd.getSenderID();
                this.reportSent = false;

                this.isForcedLoad = (cmd.getAction() == ACTION_LOAD);
                if (this.isForcedLoad) {
                    this.taskPriority = 0;
                } else {
                    this.taskPriority = 2;
                }

                // 重置防卡死状态
                this.lastTarget = null;
                this.noProgressCounter = 0;

                String actionName = "";
                if (this.commandType == ACTION_REST) actionName = "休息";
                else if (this.commandType == ACTION_MOVE) actionName = "移动";
                else if (this.commandType == ACTION_RESCUE) actionName = "救援";
                else if (this.commandType == ACTION_LOAD) actionName = "装载";
                else if (this.commandType == ACTION_UNLOAD) actionName = "卸载";
                else if (this.commandType == ACTION_AUTONOMY) actionName = "自主";

                if (this.isForcedLoad) {
                    /*System.err.println("╔══════════════════════════════════════════════════════════════╗");
                    System.err.println("║  [救护车] ID:" + agentID + " 🚨 收到强制装载命令！");
                    System.err.println("║  目标: " + this.target);
                    System.err.println("╚══════════════════════════════════════════════════════════════╝");*/
                } else {
                    //System.err.println("[救护车] ID:" + agentID + " 收到命令: " + actionName + " 目标=" + this.target);
                }
            }
        }
        return this;
    }

    @Override
    public CommandExecutor updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() >= 2) return this;

        this.pathPlanning.updateInfo(messageManager);
        this.actionTransport.updateInfo(messageManager);
        this.actionExtMove.updateInfo(messageManager);

        if (!reportSent && this.commandType != ACTION_UNKNOWN && this.target != null) {
            if (isCommandCompleted()) {
                messageManager.addMessage(new MessageReport(true, true, false, this.commanderID));
                reportSent = true;

                /*System.err.println("╔══════════════════════════════════════════════════════════════╗");
                System.err.println("║  [救护车] ID:" + this.agentInfo.getID() + " ✅ 完成任务！");
                System.err.println("║  目标: " + this.target);
                System.err.println("╚══════════════════════════════════════════════════════════════╝");*/

                if (this.commandType == ACTION_LOAD) {
                    // 装载完成，标记平民为已处理
                    if (this.target != null) {
                        processedVictims.add(this.target);
                    }
                    this.commandType = ACTION_UNLOAD;
                    this.target = null;
                    this.isForcedLoad = false;
                    //System.err.println("[救护车] ID:" + this.agentInfo.getID() + " 🔄 装载完成，转为卸载模式");
                } else {
                    this.commandType = ACTION_UNKNOWN;
                    this.target = null;
                    this.commanderID = null;
                    this.isForcedLoad = false;
                }
            }
        }
        return this;
    }

    @Override
    public CommandExecutor precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        if (this.getCountPrecompute() >= 2) return this;
        this.pathPlanning.precompute(precomputeData);
        this.actionTransport.precompute(precomputeData);
        this.actionExtMove.precompute(precomputeData);
        return this;
    }

    @Override
    public CommandExecutor resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (this.getCountResume() >= 2) return this;
        this.pathPlanning.resume(precomputeData);
        this.actionTransport.resume(precomputeData);
        this.actionExtMove.resume(precomputeData);
        return this;
    }

    @Override
    public CommandExecutor preparate() {
        super.preparate();
        if (this.getCountPreparate() >= 2) return this;
        this.pathPlanning.preparate();
        this.actionTransport.preparate();
        this.actionExtMove.preparate();
        return this;
    }

    @Override
    public CommandExecutor calc() {
        this.result = null;
        EntityID agentID = this.agentInfo.getID();
        EntityID currentPos = this.agentInfo.getPosition();

        // ========== 防卡死检查 ==========
        if (lastPosition != null && lastPosition.equals(currentPos)) {
            stuckCounter++;
            if (stuckCounter > MAX_STUCK_COUNT) {
                //System.err.println("[救护车] ID:" + agentID + " ⚠️ 卡在同一位置超过 " + MAX_STUCK_COUNT + " 步，重置状态");
                this.commandType = ACTION_UNKNOWN;
                this.target = null;
                this.stuckCounter = 0;
                this.noProgressCounter = 0;
                this.result = handleRest();
                return this;
            }
        } else {
            stuckCounter = 0;
            lastPosition = currentPos;
        }

        if (lastTarget != null && lastTarget.equals(this.target)) {
            noProgressCounter++;
            if (noProgressCounter > MAX_NO_PROGRESS) {
                //System.err.println("[救护车] ID:" + agentID + " ⚠️ 对目标 " + this.target + " 无进展超过 " + MAX_NO_PROGRESS + " 步，放弃");
                this.target = null;
                this.noProgressCounter = 0;
            }
        } else {
            noProgressCounter = 0;
            lastTarget = this.target;
        }

        // 优先处理车上伤员运输
        if (this.agentInfo.someoneOnBoard() != null) {
            Human passenger = this.agentInfo.someoneOnBoard();
            //System.err.println("[救护车] ID:" + agentID + " 🚑 车上有伤员 ID:" + passenger.getID() + "，优先执行运输任务");
            this.actionTransport.setTarget((EntityID) null);
            Action transportAction = this.actionTransport.calc().getAction();
            if (transportAction != null) {
                this.result = transportAction;
                return this;
            }
        }

        switch (this.commandType) {
            case ACTION_REST:
                this.result = handleRest();
                break;

            case ACTION_MOVE:
                if (this.target != null) {
                    this.actionExtMove.setTarget(this.target);
                    this.result = this.actionExtMove.calc().getAction();
                }
                break;

            case ACTION_RESCUE:
                if (this.target != null) {
                    this.actionTransport.setTarget(this.target);
                    this.result = this.actionTransport.calc().getAction();
                }
                break;

            case ACTION_LOAD:
                if (this.target != null) {
                    // ========== 装载前额外验证 ==========
                    StandardEntity targetEntity = this.worldInfo.getEntity(this.target);
                    if (targetEntity instanceof Human) {
                        Human victim = (Human) targetEntity;
                        
                        // 检查是否已被处理
                        if (processedVictims.contains(this.target)) {
                            //System.err.println("[救护车] ID:" + agentID + " ⚠️ 平民 " + this.target + " 已被处理，放弃");
                            this.target = null;
                            break;
                        }
                        
                        // 检查位置有效性
                        if (!victim.isPositionDefined()) {
                            //System.err.println("[救护车] ID:" + agentID + " ⚠️ 平民位置未定义，放弃");
                            this.target = null;
                            break;
                        }
                        
                        EntityID victimPos = victim.getPosition();
                        if (victimPos == null) {
                            //System.err.println("[救护车] ID:" + agentID + " ⚠️ 平民位置为 null，放弃");
                            this.target = null;
                            break;
                        }
                        
                        // 检查距离
                        double distance = this.worldInfo.getDistance(currentPos, victimPos);
                        if (distance > MAX_LOAD_DISTANCE) {
                            /*System.err.println("[救护车] ID:" + agentID + " 📏 距离平民 " + 
                                               String.format("%.1f", distance) + "，需要 " + MAX_LOAD_DISTANCE + " 以内，继续移动");*/
                            List<EntityID> path = this.pathPlanning.getResult(currentPos, victimPos);
                            if (path != null && !path.isEmpty()) {
                                this.result = new ActionMove(path);
                                return this;
                            }
                        }
                    }
                    
                    this.actionTransport.setTarget(this.target);
                    this.result = this.actionTransport.calc().getAction();
                }
                break;

            case ACTION_UNLOAD:
                this.actionTransport.setTarget((EntityID) null);
                this.result = this.actionTransport.calc().getAction();
                break;

            case ACTION_AUTONOMY:
                if (this.target != null) {
                    StandardEntity targetEntity = this.worldInfo.getEntity(this.target);
                    
                    if (targetEntity == null) {
                        //System.err.println("[救护车] ID:" + agentID + " ⚠️ 目标实体不存在，放弃任务");
                        this.target = null;
                        break;
                    }

                    if (targetEntity instanceof Human) {
                        Human human = (Human) targetEntity;
                        
                        // 检查是否已被处理
                        if (processedVictims.contains(this.target)) {
                            //System.err.println("[救护车] ID:" + agentID + " ⚠️ 平民已被处理，放弃");
                            this.target = null;
                            break;
                        }
                        
                        if (!human.isPositionDefined()) {
                            //System.err.println("[救护车] ID:" + agentID + " ⚠️ 平民位置未定义，放弃任务");
                            this.target = null;
                            break;
                        }
                        
                        EntityID victimPos = human.getPosition();
                        if (victimPos == null) {
                            //System.err.println("[救护车] ID:" + agentID + " ⚠️ 平民位置为 null，放弃任务");
                            this.target = null;
                            break;
                        }

                        boolean isBuried = human.isBuriednessDefined() && human.getBuriedness() > 0;
                        boolean hasDamage = human.isDamageDefined() && human.getDamage() > 0;
                        boolean isDead = human.isHPDefined() && human.getHP() == 0;

                        if (isDead) {
                            //System.err.println("[救护车] ID:" + agentID + " ❌ 目标平民已死亡，放弃任务");
                            this.target = null;
                            break;
                        }

                        if (isBuried) {
                            //System.err.println("[救护车] ID:" + agentID + " ⏳ 目标平民仍被掩埋，等待消防员");
                            break;
                        }

                        if (!hasDamage) {
                            //System.err.println("[救护车] ID:" + agentID + " ⚠️ 目标平民未受伤，无需装载");
                            this.target = null;
                            break;
                        }

                        StandardEntity posEntity = this.worldInfo.getEntity(victimPos);
                        if (posEntity != null && posEntity.getStandardURN() == REFUGE) {
                            //System.err.println("[救护车] ID:" + agentID + " 🏥 目标平民已在避难所，任务完成");
                            this.target = null;
                            break;
                        }
                        
                        // 检查距离
                        double distance = this.worldInfo.getDistance(currentPos, victimPos);
                        if (distance > MAX_LOAD_DISTANCE) {
                            /*System.err.println("[救护车] ID:" + agentID + " 📏 距离平民 " + 
                                               String.format("%.1f", distance) + "，需要靠近");*/
                            List<EntityID> path = this.pathPlanning.getResult(currentPos, victimPos);
                            if (path != null && !path.isEmpty()) {
                                this.result = new ActionMove(path);
                                return this;
                            }
                        }

                        this.actionTransport.setTarget(this.target);
                        this.result = this.actionTransport.calc().getAction();

                    } else if (targetEntity instanceof Area) {
                        this.actionExtMove.setTarget(this.target);
                        this.result = this.actionExtMove.calc().getAction();
                    } else {
                        //System.err.println("[救护车] ID:" + agentID + " ⚠️ 目标类型未知，放弃");
                        this.target = null;
                    }
                }
                break;

            default:
                break;
        }

        return this;
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
                if (path != null && !path.isEmpty()) {
                    return new ActionMove(path);
                } else {
                    return new ActionRest();
                }
            }
        }
        if (position.getValue() != this.target.getValue()) {
            List<EntityID> path = this.pathPlanning.getResult(position, this.target);
            if (path != null && !path.isEmpty()) {
                return new ActionMove(path);
            }
        }
        return new ActionRest();
    }

    private boolean isCommandCompleted() {
        Human agent = (Human) this.agentInfo.me();

        switch (this.commandType) {
            case ACTION_REST:
                return this.target == null ||
                       (this.agentInfo.getPosition().equals(this.target) && agent.getDamage() == 0);

            case ACTION_MOVE:
                return this.target == null ||
                       this.agentInfo.getPosition().equals(this.target);

            case ACTION_RESCUE:
                if (this.target == null) return true;
                Human human = (Human) this.worldInfo.getEntity(this.target);
                if (human == null) return true;
                return (human.isBuriednessDefined() && human.getBuriedness() == 0) ||
                       (human.isHPDefined() && human.getHP() == 0);

            case ACTION_LOAD:
                if (this.target == null) return true;
                // 检查是否已装载成功
                if (this.agentInfo.someoneOnBoard() != null) {
                    return true;
                }
                // 检查平民是否已消失（被其他救护车装载）
                Human victim = (Human) this.worldInfo.getEntity(this.target);
                if (victim == null || !victim.isPositionDefined()) {
                    return true;
                }
                return false;

            case ACTION_UNLOAD:
                return this.agentInfo.someoneOnBoard() == null;

            case ACTION_AUTONOMY:
                if (this.target == null) return true;
                StandardEntity targetEntity = this.worldInfo.getEntity(this.target);
                if (targetEntity instanceof Human) {
                    Human victim2 = (Human) targetEntity;
                    
                    if (this.agentInfo.someoneOnBoard() != null &&
                        this.agentInfo.someoneOnBoard().getID().equals(this.target)) {
                        return true;
                    }
                    if (victim2.isHPDefined() && victim2.getHP() == 0) return true;
                    if (victim2.isDamageDefined() && victim2.getDamage() == 0) return true;
                    if (victim2.isPositionDefined()) {
                        StandardEntity posEntity = this.worldInfo.getEntity(victim2.getPosition());
                        if (posEntity != null && posEntity.getStandardURN() == REFUGE) return true;
                    }
                    // 检查是否已被其他救护车装载
                    if (!victim2.isPositionDefined()) return true;
                    return false;
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