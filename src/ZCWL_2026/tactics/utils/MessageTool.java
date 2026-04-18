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
    private Set<EntityID> sentInformationMessages;   // 已发送过信息的实体（用于状态未变时的去重）

    public MessageTool(ScenarioInfo scenarioInfo, DevelopData developData) {
        this.scenarioInfo = scenarioInfo;
        this.developData = developData;
        this.sentInformationMessages = new HashSet<>();
    }

    /**
     * 将接收到的消息同步到世界模型
     */
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

    /**
     * 发送请求消息（警察通常无请求消息）
     */
    public void sendRequestMessages(AgentInfo agentInfo, WorldInfo worldInfo,
                                    ScenarioInfo scenarioInfo, MessageManager messageManager) {
        // 保留接口，不做操作
    }

    /**
     * 发送所有感知到的信息消息，无任何限制
     */
    public void sendInformationMessages(AgentInfo agentInfo, WorldInfo worldInfo,
                                        ScenarioInfo scenarioInfo, MessageManager messageManager) {
        // 发送平民信息（优先被掩埋的）
        sendCivilianInformation(agentInfo, worldInfo, messageManager);

        // 发送道路信息
        sendRoadInformation(agentInfo, worldInfo, messageManager);

        // 发送警察自身状态
        sendPoliceInformation(agentInfo, worldInfo, messageManager);
    }

    /**
     * 发送平民信息：优先发送被掩埋平民（需要消防车），其次发送已挖出受伤平民（需要救护车）
     */
    private void sendCivilianInformation(AgentInfo agentInfo, WorldInfo worldInfo,
                                         MessageManager messageManager) {
        // 获取全图平民（不受距离限制）
        Collection<StandardEntity> civilians = worldInfo.getEntitiesOfType(StandardEntityURN.CIVILIAN);

        List<Civilian> buriedCivilians = new ArrayList<>();
        List<Civilian> unburiedCivilians = new ArrayList<>();

        for (StandardEntity entity : civilians) {
            if (entity instanceof Civilian) {
                Civilian civilian = (Civilian) entity;
                // 跳过已死亡
                if (civilian.isHPDefined() && civilian.getHP() == 0) continue;
                if (!civilian.isPositionDefined()) continue;

                boolean isBuried = civilian.isBuriednessDefined() && civilian.getBuriedness() > 0;
                boolean hasDamage = civilian.isDamageDefined() && civilian.getDamage() > 0;

                if (isBuried) {
                    buriedCivilians.add(civilian);
                } else if (hasDamage) {
                    unburiedCivilians.add(civilian);
                }
            }
        }

        // 优先发送被掩埋平民
        for (Civilian civilian : buriedCivilians) {
            EntityID id = civilian.getID();
            // 只去重：如果状态未变且已发送过，则跳过（避免重复发送完全相同状态）
            if (sentInformationMessages.contains(id)) continue;

            MessageCivilian msg = new MessageCivilian(false, civilian); // 无线消息，确保送达
            messageManager.addMessage(msg);
            sentInformationMessages.add(id);

            System.err.println("[MessageTool] 📢 上报被掩埋平民: " + id +
                    " 埋压度=" + civilian.getBuriedness() +
                    " 位置=" + civilian.getPosition());
        }

        // 再发送已挖出受伤平民
        for (Civilian civilian : unburiedCivilians) {
            EntityID id = civilian.getID();
            if (sentInformationMessages.contains(id)) continue;

            MessageCivilian msg = new MessageCivilian(false, civilian);
            messageManager.addMessage(msg);
            sentInformationMessages.add(id);

            System.err.println("[MessageTool] 🚑 上报已挖出伤员: " + id +
                    " 伤害=" + civilian.getDamage() +
                    " 位置=" + civilian.getPosition());
        }
    }

    /**
     * 发送当前所在道路的阻塞信息（若道路状态未知或有阻塞）
     */
    private void sendRoadInformation(AgentInfo agentInfo, WorldInfo worldInfo,
                                     MessageManager messageManager) {
        EntityID position = agentInfo.getPosition();
        StandardEntity entity = worldInfo.getEntity(position);
        if (!(entity instanceof Road)) return;

        Road road = (Road) entity;
        EntityID roadId = road.getID();

        // 如果已经发送过且道路状态未变，则跳过（简单去重）
        if (sentInformationMessages.contains(roadId)) return;

        boolean hasBlockade = road.isBlockadesDefined() && !road.getBlockades().isEmpty();
        Blockade blockade = null;
        if (hasBlockade) {
            EntityID blockadeId = road.getBlockades().get(0);
            StandardEntity be = worldInfo.getEntity(blockadeId);
            if (be instanceof Blockade) blockade = (Blockade) be;
        }

        MessageRoad msg = new MessageRoad(
                false,          // 无线消息
                road,
                blockade,
                !hasBlockade,   // passable
                hasBlockade     // blocked
        );
        messageManager.addMessage(msg);
        sentInformationMessages.add(roadId);

        if (hasBlockade) {
            System.err.println("[MessageTool] 🚧 上报阻塞道路: " + roadId);
        } else {
            System.err.println("[MessageTool] ✅ 上报畅通道路: " + roadId);
        }
    }

    /**
     * 发送警察自身状态（位置和动作）
     */
    private void sendPoliceInformation(AgentInfo agentInfo, WorldInfo worldInfo,
                                       MessageManager messageManager) {
        PoliceForce police = (PoliceForce) agentInfo.me();
        EntityID policeId = police.getID();

        // 仅当位置变化或状态变化时重新发送（简单去重）
        if (sentInformationMessages.contains(policeId)) return;
        if (!police.isPositionDefined()) return;

        MessagePoliceForce msg = new MessagePoliceForce(
                false,
                police,
                MessagePoliceForce.ACTION_MOVE,
                null
        );
        messageManager.addMessage(msg);
        sentInformationMessages.add(policeId);

        System.err.println("[MessageTool] 👮 上报警察位置: " + policeId + " 位置=" + police.getPosition());
    }

    /**
     * 手动标记某个实体已发送过信息（用于状态变化后重置）
     */
    public void markInformationSent(EntityID entityId) {
        sentInformationMessages.add(entityId);
    }

    /**
     * 重置所有已发送记录（可用于新一轮信息广播）
     */
    public void resetInformationMessages() {
        sentInformationMessages.clear();
    }

    public void reset() {
        sentInformationMessages.clear();
    }
}