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

    public MessageTool(ScenarioInfo scenarioInfo, DevelopData developData) {
        this.scenarioInfo = scenarioInfo;
        this.developData = developData;
        this.sentRequestMessages = new HashSet<>();
        this.sentInformationMessages = new HashSet<>();
        
        // 从配置读取参数
        this.isRadioEnabled = developData.getBoolean("MessageTool.radioEnabled", true);
        this.isBroadcastEnabled = developData.getBoolean("MessageTool.broadcastEnabled", true);
        this.maxMessagesPerCycle = developData.getInteger("MessageTool.maxMessagesPerCycle", 10);
        this.scoutRange = developData.getInteger("MessageTool.scoutRange", 5000);
    }

    /**
     * 反射消息 - 更新世界信息
     */
    public void reflectMessage(AgentInfo agentInfo, WorldInfo worldInfo,
                               ScenarioInfo scenarioInfo, MessageManager messageManager) {
        // 处理所有接收到的消息
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

    /**
     * 发送请求消息
     */
    public void sendRequestMessages(AgentInfo agentInfo, WorldInfo worldInfo,
                                    ScenarioInfo scenarioInfo, MessageManager messageManager) {
        // 警察通常不需要发送请求消息，但可以留作扩展
    }

    /**
     * 发送信息消息（主要方法）
     */
    public void sendInformationMessages(AgentInfo agentInfo, WorldInfo worldInfo,
                                        ScenarioInfo scenarioInfo, MessageManager messageManager) {
        // 限制每轮最多发送的消息数量
        int messageCount = 0;
        
        // 1. 发送道路信息（高优先级）
        messageCount += sendRoadInformation(agentInfo, worldInfo, messageManager, messageCount);
        if (messageCount >= maxMessagesPerCycle) return;
        
        // 2. 发送平民信息（被困人员）
        messageCount += sendCivilianInformation(agentInfo, worldInfo, messageManager, messageCount);
        if (messageCount >= maxMessagesPerCycle) return;
        
        // 3. 发送警察状态信息
        messageCount += sendPoliceInformation(agentInfo, worldInfo, messageManager, messageCount);
    }

    /**
     * 发送道路信息
     */
    private int sendRoadInformation(AgentInfo agentInfo, WorldInfo worldInfo,
                                    MessageManager messageManager, int currentCount) {
        int sentCount = 0;
        
        // 获取当前所在道路
        EntityID position = agentInfo.getPosition();
        StandardEntity entity = worldInfo.getEntity(position);
        
        if (entity instanceof Road) {
            Road road = (Road) entity;
            
            // 如果道路有路障且未发送过信息
            if (road.isBlockadesDefined() && !road.getBlockades().isEmpty() &&
                !this.sentInformationMessages.contains(road.getID())) {
                
                // 获取第一个路障
                Blockade blockade = null;
                if (!road.getBlockades().isEmpty()) {
                    EntityID blockadeId = road.getBlockades().get(0);
                    StandardEntity blockadeEntity = worldInfo.getEntity(blockadeId);
                    if (blockadeEntity instanceof Blockade) {
                        blockade = (Blockade) blockadeEntity;
                    }
                }
                
                MessageRoad msg = new MessageRoad(
                    this.isRadioEnabled,
                    road,
                    blockade,
                    false,  // isPassable - 道路不可通行
                    true    // isSendBlockadeLocation - 发送路障位置
                );
                messageManager.addMessage(msg);
                this.sentInformationMessages.add(road.getID());
                sentCount++;
            }
            // 如果道路已清理，发送可通行信息
            else if ((!road.isBlockadesDefined() || road.getBlockades().isEmpty()) &&
                     !this.sentInformationMessages.contains(road.getID())) {
                
                MessageRoad msg = new MessageRoad(
                    this.isRadioEnabled,
                    road,
                    null,   // blockade - 没有路障
                    true,   // isPassable - 道路可通行
                    false   // isSendBlockadeLocation - 不需要发送路障位置
                );
                messageManager.addMessage(msg);
                this.sentInformationMessages.add(road.getID());
                sentCount++;
            }
        }
        
        return sentCount;
    }

    /**
     * 发送平民信息（修正 getObjectsInRange 的返回类型）
     */
    private int sendCivilianInformation(AgentInfo agentInfo, WorldInfo worldInfo,
                                        MessageManager messageManager, int currentCount) {
        int sentCount = 0;
        
        // getObjectsInRange 返回 Collection<StandardEntity>
        Collection<StandardEntity> entitiesInRange = worldInfo.getObjectsInRange(
            agentInfo.getPosition(), 
            this.scoutRange
        );
        
        for (StandardEntity entity : entitiesInRange) {
            if (sentCount + currentCount >= maxMessagesPerCycle) break;
            
            // 检查是否为平民
            if (entity instanceof Civilian) {
                Civilian civilian = (Civilian) entity;
                // 只上报被困的平民（有埋压值的平民）
                if (civilian.isBuriednessDefined() && civilian.getBuriedness() > 0 &&
                    !this.sentInformationMessages.contains(civilian.getID())) {
                    
                    MessageCivilian msg = new MessageCivilian(
                        this.isRadioEnabled,
                        civilian
                    );
                    messageManager.addMessage(msg);
                    this.sentInformationMessages.add(civilian.getID());
                    sentCount++;
                }
                // 也可以上报受伤的平民
                else if (civilian.isDamageDefined() && civilian.getDamage() > 0 &&
                         !this.sentInformationMessages.contains(civilian.getID())) {
                    
                    MessageCivilian msg = new MessageCivilian(
                        this.isRadioEnabled,
                        civilian
                    );
                    messageManager.addMessage(msg);
                    this.sentInformationMessages.add(civilian.getID());
                    sentCount++;
                }
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
        
        // 如果警察状态有变化且未发送过
        if (police.isPositionDefined() && !this.sentInformationMessages.contains(police.getID())) {
            
            // 判断当前动作
            int action = MessagePoliceForce.ACTION_MOVE; // 默认移动状态
            
            MessagePoliceForce msg = new MessagePoliceForce(
                this.isRadioEnabled,
                police,
                action,
                null  // target
            );
            messageManager.addMessage(msg);
            this.sentInformationMessages.add(police.getID());
            sentCount++;
        }
        
        return sentCount;
    }

    /**
     * 批量发送已清理的道路信息
     */
    public void sendClearedRoads(AgentInfo agentInfo, WorldInfo worldInfo,
                                 MessageManager messageManager, Set<EntityID> clearedRoads) {
        for (EntityID roadId : clearedRoads) {
            StandardEntity entity = worldInfo.getEntity(roadId);
            if (entity instanceof Road && !this.sentInformationMessages.contains(roadId)) {
                Road road = (Road) entity;
                
                MessageRoad msg = new MessageRoad(
                    this.isRadioEnabled,
                    road,
                    null,   // blockade - 没有路障
                    true,   // isPassable - 道路可通行
                    false   // isSendBlockadeLocation - 不需要发送路障位置
                );
                messageManager.addMessage(msg);
                this.sentInformationMessages.add(roadId);
            }
        }
    }

    /**
     * 批量发送发现的被困人员
     */
    public void sendDiscoveredVictims(AgentInfo agentInfo, WorldInfo worldInfo,
                                      MessageManager messageManager, Set<EntityID> victims) {
        for (EntityID victimId : victims) {
            StandardEntity entity = worldInfo.getEntity(victimId);
            if (entity instanceof Civilian && !this.sentInformationMessages.contains(victimId)) {
                Civilian civilian = (Civilian) entity;
                if (civilian.isPositionDefined() && civilian.getBuriedness() > 0) {
                    MessageCivilian msg = new MessageCivilian(
                        this.isRadioEnabled,
                        civilian
                    );
                    messageManager.addMessage(msg);
                    this.sentInformationMessages.add(victimId);
                }
            }
        }
    }

    /**
     * 发送所有范围内的平民信息（不限于被困）
     */
    public void sendAllCiviliansInRange(AgentInfo agentInfo, WorldInfo worldInfo,
                                        MessageManager messageManager) {
        Collection<StandardEntity> entitiesInRange = worldInfo.getObjectsInRange(
            agentInfo.getPosition(), 
            this.scoutRange
        );
        
        for (StandardEntity entity : entitiesInRange) {
            if (entity instanceof Civilian) {
                Civilian civilian = (Civilian) entity;
                if (!this.sentInformationMessages.contains(civilian.getID())) {
                    MessageCivilian msg = new MessageCivilian(
                        this.isRadioEnabled,
                        civilian
                    );
                    messageManager.addMessage(msg);
                    this.sentInformationMessages.add(civilian.getID());
                }
            }
        }
    }

    /**
     * 发送警察清理动作消息
     */
    public void sendPoliceClearAction(AgentInfo agentInfo, WorldInfo worldInfo,
                                      MessageManager messageManager, EntityID target) {
        PoliceForce police = (PoliceForce) agentInfo.me();
        
        MessagePoliceForce msg = new MessagePoliceForce(
            this.isRadioEnabled,
            police,
            MessagePoliceForce.ACTION_CLEAR,
            target
        );
        messageManager.addMessage(msg);
    }

    /**
     * 发送警察移动动作消息
     */
    public void sendPoliceMoveAction(AgentInfo agentInfo, WorldInfo worldInfo,
                                     MessageManager messageManager, EntityID target) {
        PoliceForce police = (PoliceForce) agentInfo.me();
        
        MessagePoliceForce msg = new MessagePoliceForce(
            this.isRadioEnabled,
            police,
            MessagePoliceForce.ACTION_MOVE,
            target
        );
        messageManager.addMessage(msg);
    }

    /**
     * 发送警察休息动作消息
     */
    public void sendPoliceRestAction(AgentInfo agentInfo, WorldInfo worldInfo,
                                     MessageManager messageManager) {
        PoliceForce police = (PoliceForce) agentInfo.me();
        
        MessagePoliceForce msg = new MessagePoliceForce(
            this.isRadioEnabled,
            police,
            MessagePoliceForce.ACTION_REST,
            null
        );
        messageManager.addMessage(msg);
    }

    /**
     * 检查是否已发送过某个实体的信息
     */
    public boolean isInformationSent(EntityID entityId) {
        return this.sentInformationMessages.contains(entityId);
    }

    /**
     * 标记信息已发送
     */
    public void markInformationSent(EntityID entityId) {
        this.sentInformationMessages.add(entityId);
    }

    /**
     * 重置发送记录
     */
    public void reset() {
        this.sentRequestMessages.clear();
        this.sentInformationMessages.clear();
    }
    
    /**
     * 部分重置（只重置信息发送记录）
     */
    public void resetInformationMessages() {
        this.sentInformationMessages.clear();
    }
    
    /**
     * 部分重置（只重置请求发送记录）
     */
    public void resetRequestMessages() {
        this.sentRequestMessages.clear();
    }
}