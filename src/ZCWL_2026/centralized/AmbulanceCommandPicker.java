package ZCWL_2026.centralized;

import adf.core.component.centralized.CommandPicker;
import adf.core.component.communication.CommunicationMessage;
import adf.core.agent.communication.standard.bundle.centralized.CommandAmbulance;
import adf.core.agent.communication.standard.bundle.centralized.CommandScout;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

/**
 * 救护车命令选择器
 * 
 * 功能：
 * 1. 接收分配器的分配结果
 * 2. 将分配结果转换为救护车可执行的命令
 */
public class AmbulanceCommandPicker extends adf.core.component.centralized.CommandPicker {
    
    private int scoutDistance;
    private Collection<CommunicationMessage> messages;
    private Map<EntityID, EntityID> allocationData;
    
    // 用于去重，避免重复发送相同命令
    private Map<EntityID, EntityID> lastAllocation;
    private int lastAllocationHash;

    public AmbulanceCommandPicker(AgentInfo ai, WorldInfo wi, ScenarioInfo si, 
                                   ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.messages = new ArrayList<>();
        this.allocationData = null;
        this.lastAllocation = new java.util.HashMap<>();
        this.lastAllocationHash = 0;
        this.scoutDistance = developData.getInteger("AmbulanceCommandPicker.scoutDistance", 40000);
        
        //System.err.println("[救护车命令选择器] 已加载");
    }

    @Override
    public CommandPicker setAllocatorResult(Map<EntityID, EntityID> allocationData) {
        this.allocationData = allocationData;
        return this;
    }

    @Override
    public CommandPicker calc() {
        this.messages.clear();
        
        if (this.allocationData == null || this.allocationData.isEmpty()) {
            if (lastAllocationHash != 0) {
                //System.err.println("[救护车命令选择器] 无分配结果");
                lastAllocationHash = 0;
                lastAllocation.clear();
            }
            return this;
        }
        
        // 计算分配哈希，检查是否变化
        int currentHash = calculateHash(this.allocationData);
        if (currentHash == lastAllocationHash) {
            // 分配结果未变化，不重复发送命令
            return this;
        }
        lastAllocationHash = currentHash;
        
        //System.err.println("[救护车命令选择器] 收到分配结果，任务数: " + this.allocationData.size());
        
        int commandCount = 0;
        int humanCount = 0;
        int areaCount = 0;
        int skipCount = 0;
        
        for (Map.Entry<EntityID, EntityID> entry : this.allocationData.entrySet()) {
            EntityID agentID = entry.getKey();
            EntityID targetID = entry.getValue();
            
            StandardEntity agent = this.worldInfo.getEntity(agentID);
            if (agent == null || agent.getStandardURN() != StandardEntityURN.AMBULANCE_TEAM) {
                //System.err.println("[救护车命令选择器] ⚠️ 警察 " + agentID + " 无效");
                skipCount++;
                continue;
            }
            
            StandardEntity target = this.worldInfo.getEntity(targetID);
            if (target == null) {
                //System.err.println("[救护车命令选择器] ⚠️ 目标 " + targetID + " 不存在");
                skipCount++;
                continue;
            }
            
            // 检查是否与上次分配相同
            EntityID lastTarget = lastAllocation.get(agentID);
            if (lastTarget != null && lastTarget.equals(targetID)) {
                // 任务相同，不重复发送
                skipCount++;
                continue;
            }
            
            if (target instanceof Human) {
                CommandAmbulance command = new CommandAmbulance(
                        true,
                        agentID,
                        target.getID(),
                        CommandAmbulance.ACTION_AUTONOMY
                );
                this.messages.add(command);
                lastAllocation.put(agentID, targetID);
                commandCount++;
                humanCount++;
                
                Human human = (Human) target;
                /*System.err.println("╔══════════════════════════════════════════════════════════════╗");
                System.err.println("║  [救护车命令选择器] 🚑 生成装载命令                         ║");
                System.err.println("║  救护车: " + agentID);
                System.err.println("║  平民: " + targetID + " 伤害=" + human.getDamage());
                System.err.println("║  位置: " + human.getPosition());
                System.err.println("╚══════════════════════════════════════════════════════════════╝");*/
                
            } else if (target instanceof Area) {
                CommandScout command = new CommandScout(
                        true,
                        agentID,
                        target.getID(),
                        this.scoutDistance
                );
                this.messages.add(command);
                lastAllocation.put(agentID, targetID);
                commandCount++;
                areaCount++;
                
                //System.err.println("[救护车命令选择器] 🔍 生成侦察命令: 救护车=" + agentID + 
                                  // " 目标区域=" + targetID);
            } else {
               // System.err.println("[救护车命令选择器] ⚠️ 目标类型未知: " + 
                                   //target.getClass().getSimpleName() + " ID=" + targetID);
                skipCount++;
            }
        }
        
        // 清理已完成的分配
        if (lastAllocation.size() > this.allocationData.size()) {
            // 移除不再存在的分配
            lastAllocation.keySet().retainAll(this.allocationData.keySet());
        }
        
        /*System.err.println("[救护车命令选择器] 生成命令完成: " +
                           "装载=" + humanCount + ", " +
                           "侦察=" + areaCount + ", " +
                           "跳过=" + skipCount);*/
        
        return this;
    }
    
    /**
     * 计算分配结果的哈希值
     */
    private int calculateHash(Map<EntityID, EntityID> data) {
        if (data == null) return 0;
        int hash = data.size();
        for (Map.Entry<EntityID, EntityID> entry : data.entrySet()) {
            hash += entry.getKey().hashCode() ^ entry.getValue().hashCode();
        }
        return hash;
    }
    
    /**
     * 重置状态（用于调试）
     */
    public void reset() {
        lastAllocation.clear();
        lastAllocationHash = 0;
        //System.err.println("[救护车命令选择器] 状态已重置");
    }

    @Override
    public Collection<CommunicationMessage> getResult() {
        return this.messages;
    }
}