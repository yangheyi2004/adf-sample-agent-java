package ZCWL_2026.tactics.utils;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.MessageUtil;
import adf.core.agent.communication.standard.bundle.information.*;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.util.*;

public class MessageTool {
    
    private ScenarioInfo scenarioInfo;
    private DevelopData developData;
    private Set<EntityID> sentRequestMessages;
    private Set<EntityID> sentInformationMessages;
    
    // 消息发送配置
    private boolean isRadioEnabled;
    private boolean isBroadcastEnabled;
    private int maxMessagesPerCycle;
    private int scoutRange;
    
    // 频率限制
    private Map<EntityID, Integer> messageSendCount;
    private Map<EntityID, Integer> lastSendTime;
    private static final int MAX_SEND_PER_ENTITY = 5;
    private static final int MAX_VOICE_MESSAGES = 2;  // 增加到2条
    private int voiceMessageCount;
    
    // 当前时间
    private int currentTime;

    public MessageTool(ScenarioInfo scenarioInfo, DevelopData developData) {
        this.scenarioInfo = scenarioInfo;
        this.developData = developData;
        this.sentRequestMessages = new HashSet<>();
        this.sentInformationMessages = new HashSet<>();
        
        this.messageSendCount = new HashMap<>();
        this.lastSendTime = new HashMap<>();
        this.voiceMessageCount = 0;
        this.currentTime = 0;
        
        this.isRadioEnabled = developData.getBoolean("MessageTool.radioEnabled", false);  // 默认使用无线
        this.isBroadcastEnabled = developData.getBoolean("MessageTool.broadcastEnabled", false);
        this.maxMessagesPerCycle = developData.getInteger("MessageTool.maxMessagesPerCycle", 5);
        this.scoutRange = developData.getInteger("MessageTool.scoutRange", 5000);
        
        System.err.println("[MessageTool] 初始化完成，最大消息数/轮: " + maxMessagesPerCycle);
    }

    public void reflectMessage(AgentInfo agentInfo, WorldInfo worldInfo,
                               ScenarioInfo scenarioInfo, MessageManager messageManager) {
        for (adf.core.component.communication.CommunicationMessage message : 
             messageManager.getReceivedMessageList()) {
            
            Class<?> messageClass = message.getClass();
            
            if (messageClass == MessageCivilian.class) {
                MessageCivilian msg = (MessageCivilian) message;
                MessageUtil.reflectMessage(worldInfo, msg);
            } else if (messageClass == MessageRoad.class) {
                MessageRoad msg = (MessageRoad) message;
                MessageUtil.reflectMessage(worldInfo, msg);
            } else if (messageClass == MessageBuilding.class) {
                MessageBuilding msg = (MessageBuilding) message;
                MessageUtil.reflectMessage(worldInfo, msg);
            } else if (messageClass == MessagePoliceForce.class) {
                MessagePoliceForce msg = (MessagePoliceForce) message;
                MessageUtil.reflectMessage(worldInfo, msg);
            } else if (messageClass == MessageAmbulanceTeam.class) {
                MessageAmbulanceTeam msg = (MessageAmbulanceTeam) message;
                MessageUtil.reflectMessage(worldInfo, msg);
            } else if (messageClass == MessageFireBrigade.class) {
                MessageFireBrigade msg = (MessageFireBrigade) message;
                MessageUtil.reflectMessage(worldInfo, msg);
            }
        }
    }

    public void sendRequestMessages(AgentInfo agentInfo, WorldInfo worldInfo,
                                    ScenarioInfo scenarioInfo, MessageManager messageManager) {
        // 警察不需要发送请求消息
    }

    /**
     * 发送信息消息 - 优先发送平民报告
     */
    public void sendInformationMessages(AgentInfo agentInfo, WorldInfo worldInfo,
                                        ScenarioInfo scenarioInfo, MessageManager messageManager) {
        this.currentTime = agentInfo.getTime();
        this.voiceMessageCount = 0;
        
        int messageCount = 0;
        
        // 优先发送平民信息（最重要的消息）
        messageCount += sendCivilianInformation(agentInfo, worldInfo, messageManager, messageCount);
        if (messageCount >= maxMessagesPerCycle) return;
        
        // 再发送道路信息
        messageCount += sendRoadInformation(agentInfo, worldInfo, messageManager, messageCount);
        if (messageCount >= maxMessagesPerCycle) return;
        
        // 最后发送状态信息
        messageCount += sendPoliceInformation(agentInfo, worldInfo, messageManager, messageCount);
    }

    /**
     * 发送平民信息 - 优先发送已挖出的平民
     */
    private int sendCivilianInformation(AgentInfo agentInfo, WorldInfo worldInfo,
                                        MessageManager messageManager, int currentCount) {
        int sentCount = 0;
        
        Collection<StandardEntity> entitiesInRange = worldInfo.getObjectsInRange(
            agentInfo.getPosition(), 
            this.scoutRange
        );
        
        // 分类平民
        List<Civilian> unburiedCivilians = new ArrayList<>();  // 已挖出的（需要救护车）
        List<Civilian> buriedCivilians = new ArrayList<>();     // 被掩埋的（需要消防员）
        
        for (StandardEntity entity : entitiesInRange) {
            if (entity instanceof Civilian) {
                Civilian civilian = (Civilian) entity;
                boolean isBuried = civilian.isBuriednessDefined() && civilian.getBuriedness() > 0;
                boolean hasDamage = civilian.isDamageDefined() && civilian.getDamage() > 0;
                
                if (!isBuried && hasDamage) {
                    unburiedCivilians.add(civilian);  // 已挖出的平民，优先发送
                } else if (isBuried) {
                    buriedCivilians.add(civilian);
                }
            }
        }
        
        // 优先发送已挖出的平民（他们需要救护车！）
        for (Civilian civilian : unburiedCivilians) {
            if (sentCount + currentCount >= maxMessagesPerCycle) break;
            
            EntityID civilianId = civilian.getID();
            
            if (!canSendMessage(civilianId)) continue;
            if (!civilian.isPositionDefined()) {
                System.err.println("[MessageTool] ⚠️ 平民 " + civilianId + " 位置未定义，跳过");
                continue;
            }
            
            // 使用无线消息（isRadio = false）确保送达
            MessageCivilian msg = new MessageCivilian(false, civilian);
            messageManager.addMessage(msg);
            this.sentInformationMessages.add(civilianId);
            recordMessageSent(civilianId);
            sentCount++;
            
            System.err.println("[MessageTool] 🚨 紧急报告已挖出平民: " + civilianId + 
                               " 伤害=" + civilian.getDamage() +
                               " 位置=" + civilian.getPosition());
        }
        
        // 再发送被掩埋的平民（需要消防员）
        for (Civilian civilian : buriedCivilians) {
            if (sentCount + currentCount >= maxMessagesPerCycle) break;
            if (voiceMessageCount >= MAX_VOICE_MESSAGES) break;
            
            EntityID civilianId = civilian.getID();
            
            if (!canSendMessage(civilianId)) continue;
            if (!civilian.isPositionDefined()) continue;
            
            MessageCivilian msg = new MessageCivilian(this.isRadioEnabled, civilian);
            messageManager.addMessage(msg);
            this.sentInformationMessages.add(civilianId);
            recordMessageSent(civilianId);
            sentCount++;
            voiceMessageCount++;
            
            System.err.println("[MessageTool] 📢 上报被困平民: " + civilianId + 
                               " 埋压度=" + civilian.getBuriedness());
        }
        
        return sentCount;
    }

    /**
     * 发送道路信息
     */
    private int sendRoadInformation(AgentInfo agentInfo, WorldInfo worldInfo,
                                    MessageManager messageManager, int currentCount) {
        int sentCount = 0;
        
        EntityID position = agentInfo.getPosition();
        StandardEntity entity = worldInfo.getEntity(position);
        
        if (entity instanceof Road) {
            Road road = (Road) entity;
            
            if (!canSendMessage(road.getID())) {
                return 0;
            }
            
            if (road.isBlockadesDefined() && !road.getBlockades().isEmpty() &&
                !this.sentInformationMessages.contains(road.getID())) {
                
                Blockade blockade = null;
                if (!road.getBlockades().isEmpty()) {
                    EntityID blockadeId = road.getBlockades().get(0);
                    StandardEntity blockadeEntity = worldInfo.getEntity(blockadeId);
                    if (blockadeEntity instanceof Blockade) {
                        blockade = (Blockade) blockadeEntity;
                    }
                }
                
                MessageRoad msg = new MessageRoad(
                    false,  // 使用无线消息
                    road,
                    blockade,
                    false,
                    true
                );
                messageManager.addMessage(msg);
                this.sentInformationMessages.add(road.getID());
                recordMessageSent(road.getID());
                sentCount++;
            }
            else if ((!road.isBlockadesDefined() || road.getBlockades().isEmpty()) &&
                     !this.sentInformationMessages.contains(road.getID())) {
                
                MessageRoad msg = new MessageRoad(
                    false,  // 使用无线消息
                    road,
                    null,
                    true,
                    false
                );
                messageManager.addMessage(msg);
                this.sentInformationMessages.add(road.getID());
                recordMessageSent(road.getID());
                sentCount++;
            }
        }
        
        return sentCount;
    }

    /**
     * 发送警察状态信息
     */
    private int sendPoliceInformation(AgentInfo agentInfo, WorldInfo worldInfo,
                                      MessageManager messageManager, int currentCount) {
        int sentCount = 0;
        
        PoliceForce police = (PoliceForce) agentInfo.me();
        EntityID policeId = police.getID();
        
        Integer lastSend = lastSendTime.get(policeId);
        if (lastSend != null && this.currentTime - lastSend < 10) {
            return 0;
        }
        
        if (police.isPositionDefined() && !this.sentInformationMessages.contains(policeId)) {
            
            int action = MessagePoliceForce.ACTION_MOVE;
            
            MessagePoliceForce msg = new MessagePoliceForce(
                false,  // 使用无线消息
                police,
                action,
                null
            );
            messageManager.addMessage(msg);
            this.sentInformationMessages.add(policeId);
            recordMessageSent(policeId);
            sentCount++;
        }
        
        return sentCount;
    }
    
    private boolean canSendMessage(EntityID entityId) {
        Integer lastTime = lastSendTime.get(entityId);
        Integer count = messageSendCount.getOrDefault(entityId, 0);
        
        if (count >= MAX_SEND_PER_ENTITY) {
            return false;
        }
        
        if (lastTime != null && this.currentTime - lastTime < 3) {
            return false;
        }
        
        return true;
    }
    
    private void recordMessageSent(EntityID entityId) {
        lastSendTime.put(entityId, this.currentTime);
        messageSendCount.put(entityId, messageSendCount.getOrDefault(entityId, 0) + 1);
    }

    public void sendClearedRoads(AgentInfo agentInfo, WorldInfo worldInfo,
                                 MessageManager messageManager, Set<EntityID> clearedRoads) {
        int sentCount = 0;
        for (EntityID roadId : clearedRoads) {
            if (sentCount >= maxMessagesPerCycle) break;
            
            StandardEntity entity = worldInfo.getEntity(roadId);
            if (entity instanceof Road && !this.sentInformationMessages.contains(roadId)) {
                Road road = (Road) entity;
                
                if (canSendMessage(roadId)) {
                    MessageRoad msg = new MessageRoad(
                        false,
                        road,
                        null,
                        true,
                        false
                    );
                    messageManager.addMessage(msg);
                    this.sentInformationMessages.add(roadId);
                    recordMessageSent(roadId);
                    sentCount++;
                }
            }
        }
    }

    public void sendDiscoveredVictims(AgentInfo agentInfo, WorldInfo worldInfo,
                                      MessageManager messageManager, Set<EntityID> victims) {
        int sentCount = 0;
        for (EntityID victimId : victims) {
            if (sentCount >= maxMessagesPerCycle) break;
            if (voiceMessageCount >= MAX_VOICE_MESSAGES) break;
            
            StandardEntity entity = worldInfo.getEntity(victimId);
            if (entity instanceof Civilian && !this.sentInformationMessages.contains(victimId)) {
                Civilian civilian = (Civilian) entity;
                if (civilian.isPositionDefined() && civilian.getBuriedness() > 0) {
                    if (canSendMessage(victimId)) {
                        MessageCivilian msg = new MessageCivilian(false, civilian);
                        messageManager.addMessage(msg);
                        this.sentInformationMessages.add(victimId);
                        recordMessageSent(victimId);
                        sentCount++;
                        voiceMessageCount++;
                    }
                }
            }
        }
    }

    public void sendAllCiviliansInRange(AgentInfo agentInfo, WorldInfo worldInfo,
                                        MessageManager messageManager) {
        int sentCount = 0;
        Collection<StandardEntity> entitiesInRange = worldInfo.getObjectsInRange(
            agentInfo.getPosition(), 
            this.scoutRange
        );
        
        for (StandardEntity entity : entitiesInRange) {
            if (sentCount >= maxMessagesPerCycle) break;
            if (voiceMessageCount >= MAX_VOICE_MESSAGES) break;
            
            if (entity instanceof Civilian) {
                Civilian civilian = (Civilian) entity;
                EntityID civilianId = civilian.getID();
                
                if (!this.sentInformationMessages.contains(civilianId) && canSendMessage(civilianId)) {
                    MessageCivilian msg = new MessageCivilian(false, civilian);
                    messageManager.addMessage(msg);
                    this.sentInformationMessages.add(civilianId);
                    recordMessageSent(civilianId);
                    sentCount++;
                    voiceMessageCount++;
                }
            }
        }
    }

    public boolean isInformationSent(EntityID entityId) {
        return this.sentInformationMessages.contains(entityId);
    }

    public void markInformationSent(EntityID entityId) {
        this.sentInformationMessages.add(entityId);
        recordMessageSent(entityId);
    }

    public void reset() {
        this.sentRequestMessages.clear();
        this.sentInformationMessages.clear();
        this.messageSendCount.clear();
        this.lastSendTime.clear();
        this.voiceMessageCount = 0;
    }
    
    public void resetInformationMessages() {
        this.sentInformationMessages.clear();
        this.voiceMessageCount = 0;
    }
    
    public void resetRequestMessages() {
        this.sentRequestMessages.clear();
    }
    
    public int getVoiceMessageCount() {
        return voiceMessageCount;
    }
}