package ZCWL_2026.centralized;

import adf.core.agent.action.Action;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.action.common.ActionRest;
import adf.core.agent.action.police.ActionClear;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.centralized.CommandPolice;
import adf.core.agent.communication.standard.bundle.centralized.MessageReport;
import adf.core.agent.communication.standard.bundle.information.MessageRoad;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.centralized.CommandExecutor;
import adf.core.component.extaction.ExtAction;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.standard.entities.PoliceForce;
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.worldmodel.EntityID;

import java.util.*;

public class PoliceCommandExecutor extends CommandExecutor<CommandPolice> {

    private static final int MAX_RETRY = 5;
    private static final int MAX_STUCK_CYCLES = 10;

    private PathPlanning pathPlanning;
    private ExtAction actionExtClear;
    private ExtAction actionExtMove;
    private MessageManager msgManager;

    private EntityID currentTarget;
    private int retryCount;
    private EntityID commanderID;
    private boolean reportSent;

    private EntityID lastPosition;
    private int stuckCount;

    public PoliceCommandExecutor(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                                 ModuleManager mm, DevelopData dd) {
        super(ai, wi, si, mm, dd);

        switch (si.getMode()) {
            case PRECOMPUTATION_PHASE:
            case PRECOMPUTED:
            case NON_PRECOMPUTE:
                this.pathPlanning = mm.getModule(
                    "PoliceCommandExecutor.PathPlanning",
                    "ZCWL_2026.module.algorithm.PathPlanning");
                this.actionExtClear = mm.getExtAction(
                    "PoliceCommandExecutor.ActionExtClear",
                    "ZCWL_2026.extraction.ActionExtClear");
                this.actionExtMove = mm.getExtAction(
                    "PoliceCommandExecutor.ActionExtMove",
                    "ZCWL_2026.extraction.ActionExtMove");
                break;
        }
    }

    @Override
    public CommandExecutor<CommandPolice> setCommand(CommandPolice command) {
        EntityID myId = agentInfo.getID();
        if (command.isToIDDefined() && Objects.equals(command.getToID(), myId)) {
            commanderID = command.getSenderID();
            reportSent = false;
            lastPosition = null;
            stuckCount = 0;
            retryCount = 0;

            if (command.getAction() == CommandPolice.ACTION_CLEAR && command.getTargetID() != null) {
                currentTarget = command.getTargetID();
                //System.err.printf("[警察执行器] 警察 ID=%s 接受清理命令: 道路=%s%n", myId, currentTarget);
            } else if (command.getAction() == CommandPolice.ACTION_MOVE && command.getTargetID() != null) {
                currentTarget = command.getTargetID();
                //System.err.printf("[警察执行器] 警察 ID=%s 接受移动命令: 目标=%s%n", myId, currentTarget);
            } else if (command.getAction() == CommandPolice.ACTION_REST) {
                currentTarget = null;
                //System.err.printf("[警察执行器] 警察 ID=%s 接受休息命令%n", myId);
            }
        }
        return this;
    }

    @Override
    public CommandExecutor<CommandPolice> updateInfo(MessageManager mm) {
        super.updateInfo(mm);
        if (getCountUpdateInfo() >= 2) return this;
        this.msgManager = mm;
        if (pathPlanning != null) pathPlanning.updateInfo(mm);
        if (actionExtClear != null) actionExtClear.updateInfo(mm);
        if (actionExtMove != null) actionExtMove.updateInfo(mm);
        return this;
    }

    @Override
    public CommandExecutor<CommandPolice> calc() {
        this.result = null;
        EntityID myId = agentInfo.getID();

        if (currentTarget == null) return this;

        // 如果已经完成并发送了报告，直接返回 null，不再生成动作
        if (reportSent) {
            currentTarget = null;
            return this;
        }

        // 1. 标准完成检查
        if (isRoadClear(currentTarget)) {
            sendCompletionReport(myId);
            currentTarget = null;
            return this;
        }

        // 2. 停滞检测与未定义道路处理
        EntityID currentPos = agentInfo.getPosition();
        if (currentPos.equals(currentTarget)) {
            if (lastPosition != null && lastPosition.equals(currentPos)) {
                stuckCount++;
                if (stuckCount >= MAX_STUCK_CYCLES) {
                    if (isRoadClear(currentTarget)) {
                        System.err.printf("[警察执行器] 警察 ID=%s 停滞 %d 步，道路已清理，完成%n", myId, stuckCount);
                        sendCompletionReport(myId);
                        currentTarget = null;
                        stuckCount = 0;
                        return this;
                    } else {
                        Road r = (Road) worldInfo.getEntity(currentTarget);
                        if (r != null && !r.isBlockadesDefined()) {
                            System.err.printf("[警察执行器] 警察 ID=%s 在未定义道路 %s 停滞，强制完成%n", myId, currentTarget);
                            sendCompletionReport(myId);
                            currentTarget = null;
                            stuckCount = 0;
                            return this;
                        }
                    }
                }
            } else {
                stuckCount = 0;
                lastPosition = currentPos;
            }
        } else {
            stuckCount = 0;
            lastPosition = currentPos;
        }

        // 3. 执行清理或移动
        Action action = executeClear(currentTarget);
        if (action != null) {
            this.result = action;
            if (action instanceof ActionClear && isRoadClear(currentTarget)) {
                sendCompletionReport(myId);
                currentTarget = null;
            }
            return this;
        }

        // 4. 尝试移动
        Action moveAction = moveToTarget(currentTarget);
        if (moveAction != null) {
            this.result = moveAction;
            retryCount = 0;
            //System.err.printf("[警察执行器] 警察 ID=%s 执行移动: 目标=%s%n", myId, currentTarget);
            return this;
        }

        // 5. 备用路径规划
        List<EntityID> path = pathPlanning.getResult(currentPos, currentTarget);
        if (path != null && !path.isEmpty()) {
            this.result = new ActionMove(path);
            retryCount = 0;
            //System.err.printf("[警察执行器] 警察 ID=%s 使用备用路径移动: 目标=%s%n", myId, currentTarget);
            return this;
        }

        // 6. 重试或放弃
        retryCount++;
        System.err.printf("[警察执行器] 警察 ID=%s 无法移动到目标 %s，重试 %d/%d%n", myId, currentTarget, retryCount, MAX_RETRY);
        if (retryCount >= MAX_RETRY) {
            System.err.printf("[警察执行器] 警察 ID=%s 放弃任务 %s%n", myId, currentTarget);
            if (!reportSent && commanderID != null) {
                msgManager.addMessage(new MessageReport(true, true, true, commanderID));
                reportSent = true;
                // 注意：放弃任务时也广播道路清理？这取决于策略。这里不广播，仅通知中心。
            }
            currentTarget = null;
            retryCount = 0;
        }

        return this;
    }

    private Action executeClear(EntityID roadId) {
        if (actionExtClear == null) return null;
        Action action = actionExtClear.setTarget(roadId).calc().getAction();
        if (action == null) {
            StandardEntity e = worldInfo.getEntity(roadId);
            if (e instanceof Road) {
                Road r = (Road) e;
                int x = r.getX();
                int y = r.getY();
                //System.err.printf("[警察执行器] 警察 ID=%s 使用默认清理动作: road=%s (%d,%d)%n",
                //        agentInfo.getID(), roadId, x, y);
                return new ActionClear(x, y);
            }
        }
        return action;
    }

    private Action moveToTarget(EntityID target) {
        if (actionExtMove == null) return null;
        return actionExtMove.setTarget(target).calc().getAction();
    }

    private boolean isRoadClear(EntityID roadId) {
        if (roadId == null) return true;
        StandardEntity e = worldInfo.getEntity(roadId);
        if (!(e instanceof Road)) return true;
        Road r = (Road) e;
        List<EntityID> blockades = r.getBlockades();
        return blockades != null && blockades.isEmpty();
    }

    private void sendCompletionReport(EntityID myId) {
        if (reportSent) return; // 已经发送过，避免重复
        if (commanderID == null) return;

        // 发送完成报告给指挥官
        msgManager.addMessage(new MessageReport(true, true, true, commanderID));
        reportSent = true;

        // 广播道路已清理，以便其他智能体更新信息
        broadcastRoadClear(currentTarget);

        //System.err.printf("[警察执行器] 警察 ID=%s 任务完成: road=%s%n", myId, currentTarget);
    }

    private void broadcastRoadClear(EntityID roadId) {
        if (msgManager == null || roadId == null) return;
        Road road = (Road) worldInfo.getEntity(roadId);
        if (road == null) return;
        MessageRoad msg = new MessageRoad(true, road, null, true, false);
        msgManager.addMessage(msg);
    }

    @Override
    public CommandExecutor<CommandPolice> precompute(PrecomputeData pd) { return this; }
    @Override
    public CommandExecutor<CommandPolice> resume(PrecomputeData pd) { return this; }
    @Override
    public CommandExecutor<CommandPolice> preparate() { return this; }
}