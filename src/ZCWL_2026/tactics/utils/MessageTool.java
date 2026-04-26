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
    
    private Map<EntityID, Integer> lastBuriedness;
    private Map<EntityID, Integer> lastDamage;
    private Map<EntityID, Boolean> lastRoadBlocked;
    private Map<EntityID, EntityID> lastPolicePosition;
    
    private int lastSendTime = 0;
    private static final int FULL_SEND_INTERVAL = 20;

    public MessageTool(ScenarioInfo scenarioInfo, DevelopData developData) {
        this.scenarioInfo = scenarioInfo;
        this.developData = developData;
        this.lastBuriedness = new HashMap<>();
        this.lastDamage = new HashMap<>();
        this.lastRoadBlocked = new HashMap<>();
        this.lastPolicePosition = new HashMap<>();
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
    }

    public void sendInformationMessages(AgentInfo agentInfo, WorldInfo worldInfo,
                                        ScenarioInfo scenarioInfo, MessageManager messageManager) {
        int currentTime = agentInfo.getTime();
        boolean forceSend = (currentTime - lastSendTime >= FULL_SEND_INTERVAL);
        
        sendCivilianInformation(agentInfo, worldInfo, messageManager, forceSend);
        sendRoadInformation(agentInfo, worldInfo, messageManager, forceSend);
        sendPoliceInformation(agentInfo, worldInfo, messageManager, forceSend);
        
        lastSendTime = currentTime;
    }

    private void sendCivilianInformation(AgentInfo agentInfo, WorldInfo worldInfo,
                                         MessageManager messageManager, boolean forceSend) {
        Collection<StandardEntity> civilians = worldInfo.getEntitiesOfType(StandardEntityURN.CIVILIAN);
        
        for (StandardEntity entity : civilians) {
            if (!(entity instanceof Civilian)) continue;
            Civilian civilian = (Civilian) entity;
            EntityID id = civilian.getID();
            
            if (civilian.isHPDefined() && civilian.getHP() == 0) continue;
            if (!civilian.isPositionDefined()) continue;

            boolean isBuried = civilian.isBuriednessDefined() && civilian.getBuriedness() > 0;
            int buriedness = civilian.isBuriednessDefined() ? civilian.getBuriedness() : 0;
            int damage = civilian.isDamageDefined() ? civilian.getDamage() : 0;

            Integer lastBuried = lastBuriedness.get(id);
            Integer lastDmg = lastDamage.get(id);
            
            boolean changed = (lastBuried == null || lastBuried != buriedness ||
                               lastDmg == null || lastDmg != damage);
            
            if (!changed && !forceSend) continue;

            lastBuriedness.put(id, buriedness);
            lastDamage.put(id, damage);

            MessageCivilian msg = new MessageCivilian(true, civilian);
            messageManager.addMessage(msg);
        }
    }

    private void sendRoadInformation(AgentInfo agentInfo, WorldInfo worldInfo,
                                     MessageManager messageManager, boolean forceSend) {
        EntityID position = agentInfo.getPosition();
        StandardEntity entity = worldInfo.getEntity(position);
        if (!(entity instanceof Road)) return;

        Road road = (Road) entity;
        EntityID roadId = road.getID();

        boolean hasBlockade = road.isBlockadesDefined() && !road.getBlockades().isEmpty();
        Boolean lastBlocked = lastRoadBlocked.get(roadId);
        
        boolean changed = (lastBlocked == null || lastBlocked != hasBlockade);
        
        if (!changed && !forceSend) return;
        
        lastRoadBlocked.put(roadId, hasBlockade);

        MessageRoad msg = new MessageRoad(true, road, null, !hasBlockade, hasBlockade);
        messageManager.addMessage(msg);
    }

    private void sendPoliceInformation(AgentInfo agentInfo, WorldInfo worldInfo,
                                       MessageManager messageManager, boolean forceSend) {
        PoliceForce police = (PoliceForce) agentInfo.me();
        EntityID policeId = police.getID();
        
        if (!police.isPositionDefined()) return;
        
        EntityID currentPos = police.getPosition();
        EntityID lastPos = lastPolicePosition.get(policeId);
        
        boolean changed = (lastPos == null || !lastPos.equals(currentPos));
        
        if (!changed && !forceSend) return;
        
        lastPolicePosition.put(policeId, currentPos);

        MessagePoliceForce msg = new MessagePoliceForce(
                true, police, MessagePoliceForce.ACTION_MOVE, null
        );
        messageManager.addMessage(msg);
    }

    public void reset() {
        lastBuriedness.clear();
        lastDamage.clear();
        lastRoadBlocked.clear();
        lastPolicePosition.clear();
        lastSendTime = 0;
    }
}
