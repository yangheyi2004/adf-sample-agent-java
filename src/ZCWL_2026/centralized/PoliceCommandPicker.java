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
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

import java.util.*;

/**
 * 警车命令选择器 - 完整优化版
 * 
 * 功能：
 * 1. 接收分配器的分配结果
 * 2. 避免重复发送相同的命令
 * 3. 检测任务完成状态，自动清理
 * 4. 任务去重，防止警察来回跑
 */
public class PoliceCommandPicker extends adf.core.component.centralized.CommandPicker {

    private Collection<CommunicationMessage> messages;
    private Map<EntityID, EntityID> allocationData;
    
    // 任务持久化
    private Map<EntityID, EntityID> activeTasks;           // 警察 -> 当前正在执行的任务
    private Map<EntityID, Integer> taskSendCount;          // 任务 -> 已发送次数
    private Set<EntityID> completedTasks;                  // 已完成的任务
    private int lastAllocationHash;                        // 上次分配的哈希值
    private int sameAllocationCount;                       // 相同分配连续次数

    public PoliceCommandPicker(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                                ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.messages = new ArrayList<>();
        this.allocationData = null;
        this.activeTasks = new HashMap<>();
        this.taskSendCount = new HashMap<>();
        this.completedTasks = new HashSet<>();
        this.lastAllocationHash = 0;
        this.sameAllocationCount = 0;
        
        System.err.println("[PoliceCommandPicker] 已加载（优化版 v2）");
    }

    @Override
    public CommandPicker setAllocatorResult(Map<EntityID, EntityID> allocationData) {
        // 计算分配结果的哈希值，判断是否变化
        int newHash = calculateAllocationHash(allocationData);
        
        if (newHash != lastAllocationHash) {
            this.allocationData = allocationData;
            this.lastAllocationHash = newHash;
            this.sameAllocationCount = 0;
            System.err.println("[PoliceCommandPicker] 收到新的分配结果，任务数: " + 
                               (allocationData != null ? allocationData.size() : 0));
        } else {
            this.sameAllocationCount++;
            // 每10轮输出一次日志，减少噪音
            if (sameAllocationCount % 10 == 0 && sameAllocationCount > 0) {
                // System.err.println("[PoliceCommandPicker] 分配结果未变化，已持续 " + sameAllocationCount + " 轮");
            }
        }
        
        return this;
    }
    
    /**
     * 计算分配结果的哈希值
     */
    private int calculateAllocationHash(Map<EntityID, EntityID> data) {
        if (data == null) return 0;
        int hash = data.size();
        for (Map.Entry<EntityID, EntityID> entry : data.entrySet()) {
            hash += entry.getKey().hashCode() ^ entry.getValue().hashCode();
        }
        return hash;
    }

    @Override
    public CommandPicker calc() {
        this.messages.clear();
        
        if (this.allocationData == null || this.allocationData.isEmpty()) {
            return this;
        }
        
        // 更新已完成任务
        updateCompletedTasks();
        
        int newCommandCount = 0;
        int skipCount = 0;
        int completedSkipCount = 0;
        
        for (Map.Entry<EntityID, EntityID> entry : this.allocationData.entrySet()) {
            EntityID agentID = entry.getKey();
            EntityID targetID = entry.getValue();
            
            // 检查任务是否已完成
            if (completedTasks.contains(targetID)) {
                // 清除警察的活跃任务
                if (activeTasks.containsKey(agentID)) {
                    activeTasks.remove(agentID);
                }
                completedSkipCount++;
                continue;
            }
            
            // 检查任务是否有效（道路仍有路障）
            if (!isTaskValid(targetID)) {
                // 标记为已完成
                completedTasks.add(targetID);
                if (activeTasks.containsKey(agentID)) {
                    activeTasks.remove(agentID);
                }
                completedSkipCount++;
                continue;
            }
            
            // 检查警察是否已有活跃任务
            EntityID currentTask = activeTasks.get(agentID);
            
            // 如果活跃任务与分配任务相同，不重复发送
            if (currentTask != null && currentTask.equals(targetID)) {
                skipCount++;
                continue;
            }
            
            // 如果活跃任务不同，需要发送新命令
            // 但检查是否刚发送过这个任务（避免频繁切换）
            Integer sendCount = taskSendCount.get(targetID);
            if (sendCount != null && sendCount > 5) {
                // 任务已发送多次，可能存在问题，跳过
                continue;
            }
            
            // 生成命令
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
                    
                    // 更新状态
                    activeTasks.put(agentID, targetID);
                    taskSendCount.put(targetID, taskSendCount.getOrDefault(targetID, 0) + 1);
                    newCommandCount++;
                }
            }
        }
        
        // 只在有新命令时输出详细日志
        if (newCommandCount > 0) {
            System.err.println("[PoliceCommandPicker] 📡 生成命令: " +
                               "新命令=" + newCommandCount + ", " +
                               "跳过(活跃)=" + skipCount + ", " +
                               "跳过(完成)=" + completedSkipCount);
        }
        
        return this;
    }
    
    /**
     * 更新已完成的任务（基于道路状态）
     */
    private void updateCompletedTasks() {
        // 检查已记录的任务是否又有路障了
        Set<EntityID> toRemove = new HashSet<>();
        for (EntityID taskId : completedTasks) {
            if (isTaskValid(taskId)) {
                toRemove.add(taskId);
            }
        }
        completedTasks.removeAll(toRemove);
        
        // 检查当前分配的任务
        if (allocationData != null) {
            for (EntityID targetID : allocationData.values()) {
                if (!isTaskValid(targetID)) {
                    if (!completedTasks.contains(targetID)) {
                        completedTasks.add(targetID);
                        // System.err.println("[PoliceCommandPicker] 任务 " + targetID + " 已完成");
                    }
                }
            }
        }
        
        // 清理已完成任务的发送计数
        for (EntityID completed : completedTasks) {
            taskSendCount.remove(completed);
        }
        
        // 清理已完成任务的活跃警察关联
        for (Map.Entry<EntityID, EntityID> entry : new HashMap<>(activeTasks).entrySet()) {
            if (completedTasks.contains(entry.getValue())) {
                activeTasks.remove(entry.getKey());
            }
        }
    }
    
    /**
     * 检查任务是否有效（道路有路障）
     */
    private boolean isTaskValid(EntityID targetId) {
        if (targetId == null) return false;
        
        StandardEntity entity = this.worldInfo.getEntity(targetId);
        if (entity == null) return false;
        
        if (entity instanceof Road) {
            Road road = (Road) entity;
            return road.isBlockadesDefined() && !road.getBlockades().isEmpty();
        }
        
        // 非道路类型默认有效
        return true;
    }
    
    /**
     * 获取警察当前任务
     */
    public EntityID getPoliceTask(EntityID policeId) {
        return activeTasks.get(policeId);
    }
    
    /**
     * 手动标记任务完成
     */
    public void markTaskCompleted(EntityID taskId) {
        if (!completedTasks.contains(taskId)) {
            completedTasks.add(taskId);
            taskSendCount.remove(taskId);
            
            // 清除相关警察的活跃任务
            for (Map.Entry<EntityID, EntityID> entry : new HashMap<>(activeTasks).entrySet()) {
                if (taskId.equals(entry.getValue())) {
                    activeTasks.remove(entry.getKey());
                }
            }
            
            System.err.println("[PoliceCommandPicker] 手动标记任务完成: " + taskId);
        }
    }
    
    /**
     * 获取已完成任务集合
     */
    public Set<EntityID> getCompletedTasks() {
        return new HashSet<>(completedTasks);
    }
    
    /**
     * 重置分配记录（用于调试）
     */
    public void resetAssignmentRecords() {
        activeTasks.clear();
        taskSendCount.clear();
        completedTasks.clear();
        sameAllocationCount = 0;
        System.err.println("[PoliceCommandPicker] 分配记录已重置");
    }

    @Override
    public Collection<CommunicationMessage> getResult() {
        return this.messages;
    }
}