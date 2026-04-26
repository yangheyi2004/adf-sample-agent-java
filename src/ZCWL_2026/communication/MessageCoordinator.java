package ZCWL_2026.communication;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.StandardMessage;
import adf.core.agent.communication.standard.bundle.StandardMessagePriority;
import adf.core.agent.communication.standard.bundle.centralized.MessageReport;
import adf.core.agent.communication.standard.bundle.centralized.CommandAmbulance;
import adf.core.agent.communication.standard.bundle.centralized.CommandFire;
import adf.core.agent.communication.standard.bundle.centralized.CommandPolice;
import adf.core.agent.communication.standard.bundle.centralized.CommandScout;
import adf.core.agent.communication.standard.bundle.information.MessageAmbulanceTeam;
import adf.core.agent.communication.standard.bundle.information.MessageBuilding;
import adf.core.agent.communication.standard.bundle.information.MessageCivilian;
import adf.core.agent.communication.standard.bundle.information.MessageFireBrigade;
import adf.core.agent.communication.standard.bundle.information.MessagePoliceForce;
import adf.core.agent.communication.standard.bundle.information.MessageRoad;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.component.communication.CommunicationMessage;
import rescuecore2.standard.entities.StandardEntityURN;

import java.util.ArrayList;
import java.util.List;

public class MessageCoordinator extends adf.core.component.communication.MessageCoordinator {

    @Override
    public void coordinate(AgentInfo agentInfo, WorldInfo worldInfo, ScenarioInfo scenarioInfo,
                           MessageManager messageManager,
                           ArrayList<CommunicationMessage> sendMessageList,
                           List<List<CommunicationMessage>> channelSendMessageList) {

        StandardEntityURN agentType = getAgentType(agentInfo, worldInfo);
        
        // 诊断日志：打印当前智能体和待发送消息概况
        /*System.err.println("============================================================");
        System.err.println("[MessageCoordinator] 时间=" + agentInfo.getTime() + 
                           " 智能体=" + agentType + " ID=" + agentInfo.getID());
        System.err.println("[MessageCoordinator] 待发送消息总数=" + sendMessageList.size());*/
        
        int commandPoliceCount = 0;
        for (CommunicationMessage msg : sendMessageList) {
            if (msg instanceof CommandPolice) {
                commandPoliceCount++;
                CommandPolice cmd = (CommandPolice) msg;
                //System.err.println("[MessageCoordinator]   -> CommandPolice: toID=" + cmd.getToID() + 
                  //                 ", target=" + cmd.getTargetID() + ", action=" + cmd.getAction());
            }
        }
        if (commandPoliceCount > 0) {
           // System.err.println("[MessageCoordinator] 包含 " + commandPoliceCount + " 条警察命令");
        }
       // System.err.println("============================================================");

        // 分类列表
        ArrayList<CommunicationMessage> policeMessages = new ArrayList<>();
        ArrayList<CommunicationMessage> ambulanceMessages = new ArrayList<>();
        ArrayList<CommunicationMessage> fireBrigadeMessages = new ArrayList<>();
        ArrayList<CommunicationMessage> voiceMessages = new ArrayList<>();

        for (CommunicationMessage msg : sendMessageList) {
            if (msg instanceof StandardMessage && !((StandardMessage) msg).isRadio()) {
                voiceMessages.add(msg);
            } else {
                if (msg instanceof MessageBuilding) {
                    fireBrigadeMessages.add(msg);
                } else if (msg instanceof MessageCivilian) {
                    ambulanceMessages.add(msg);
                } else if (msg instanceof MessageRoad) {
                    fireBrigadeMessages.add(msg);
                    ambulanceMessages.add(msg);
                    policeMessages.add(msg);
                } else if (msg instanceof CommandAmbulance) {
                    ambulanceMessages.add(msg);
                } else if (msg instanceof CommandFire) {
                    fireBrigadeMessages.add(msg);
                } else if (msg instanceof CommandPolice) {
                    policeMessages.add(msg);
                } else if (msg instanceof CommandScout) {
                    if (agentType == StandardEntityURN.FIRE_STATION) {
                        fireBrigadeMessages.add(msg);
                    } else if (agentType == StandardEntityURN.POLICE_OFFICE) {
                        policeMessages.add(msg);
                    } else if (agentType == StandardEntityURN.AMBULANCE_CENTRE) {
                        ambulanceMessages.add(msg);
                    }
                } else if (msg instanceof MessageReport) {
                    if (agentType == StandardEntityURN.FIRE_BRIGADE) {
                        fireBrigadeMessages.add(msg);
                    } else if (agentType == StandardEntityURN.POLICE_FORCE) {
                        policeMessages.add(msg);
                    } else if (agentType == StandardEntityURN.AMBULANCE_TEAM) {
                        ambulanceMessages.add(msg);
                    }
                } else if (msg instanceof MessageFireBrigade) {
                    fireBrigadeMessages.add(msg);
                    ambulanceMessages.add(msg);
                    policeMessages.add(msg);
                } else if (msg instanceof MessagePoliceForce) {
                    ambulanceMessages.add(msg);
                    policeMessages.add(msg);
                } else if (msg instanceof MessageAmbulanceTeam) {
                    ambulanceMessages.add(msg);
                    policeMessages.add(msg);
                }
            }
        }

        // 打印分类结果
       /*  System.err.println("[MessageCoordinator] 分类结果: police=" + policeMessages.size() +
                           ", ambulance=" + ambulanceMessages.size() +
                           ", fire=" + fireBrigadeMessages.size() +
                           ", voice=" + voiceMessages.size());*/

        // 分配到无线频道
        if (scenarioInfo.getCommsChannelsCount() > 1) {
            int[] channelSize = new int[scenarioInfo.getCommsChannelsCount() - 1];
            setSendMessages(scenarioInfo, StandardEntityURN.POLICE_FORCE, agentInfo, worldInfo,
                            policeMessages, channelSendMessageList, channelSize);
            setSendMessages(scenarioInfo, StandardEntityURN.AMBULANCE_TEAM, agentInfo, worldInfo,
                            ambulanceMessages, channelSendMessageList, channelSize);
            setSendMessages(scenarioInfo, StandardEntityURN.FIRE_BRIGADE, agentInfo, worldInfo,
                            fireBrigadeMessages, channelSendMessageList, channelSize);
            
            // 打印最终各频道消息数
            for (int i = 1; i < channelSendMessageList.size(); i++) {
                //System.err.println("[MessageCoordinator] 频道" + i + " 消息数=" + 
                                   //channelSendMessageList.get(i).size());
            }
        }

        // 语音频道处理
        ArrayList<StandardMessage> voiceMessageLowList = new ArrayList<>();
        ArrayList<StandardMessage> voiceMessageNormalList = new ArrayList<>();
        ArrayList<StandardMessage> voiceMessageHighList = new ArrayList<>();

        for (CommunicationMessage msg : voiceMessages) {
            if (msg instanceof StandardMessage) {
                StandardMessage m = (StandardMessage) msg;
                switch (m.getSendingPriority()) {
                    case LOW:
                        voiceMessageLowList.add(m);
                        break;
                    case NORMAL:
                        voiceMessageNormalList.add(m);
                        break;
                    case HIGH:
                        voiceMessageHighList.add(m);
                        break;
                }
            }
        }

        channelSendMessageList.get(0).addAll(voiceMessageHighList);
        channelSendMessageList.get(0).addAll(voiceMessageNormalList);
        channelSendMessageList.get(0).addAll(voiceMessageLowList);
    }

    protected int[] getChannelsByAgentType(StandardEntityURN agentType, AgentInfo agentInfo,
                                           WorldInfo worldInfo, ScenarioInfo scenarioInfo, int channelIndex) {
        int numChannels = scenarioInfo.getCommsChannelsCount() - 1;
        int maxChannelCount = isPlatoonAgent(agentInfo, worldInfo)
                ? scenarioInfo.getCommsChannelsMaxPlatoon()
                : scenarioInfo.getCommsChannelsMaxOffice();
        int[] channels = new int[maxChannelCount];
        for (int i = 0; i < maxChannelCount; i++) {
            channels[i] = ChannelSubscriber.getChannelNumber(agentType, i, numChannels);
        }
        return channels;
    }

    protected boolean isPlatoonAgent(AgentInfo agentInfo, WorldInfo worldInfo) {
        StandardEntityURN agentType = getAgentType(agentInfo, worldInfo);
        return agentType == StandardEntityURN.FIRE_BRIGADE ||
               agentType == StandardEntityURN.POLICE_FORCE ||
               agentType == StandardEntityURN.AMBULANCE_TEAM;
    }

    protected StandardEntityURN getAgentType(AgentInfo agentInfo, WorldInfo worldInfo) {
        return worldInfo.getEntity(agentInfo.getID()).getStandardURN();
    }

    protected void setSendMessages(ScenarioInfo scenarioInfo, StandardEntityURN agentType,
                                   AgentInfo agentInfo, WorldInfo worldInfo,
                                   List<CommunicationMessage> messages,
                                   List<List<CommunicationMessage>> channelSendMessageList,
                                   int[] channelSize) {
        int channelIndex = 0;
        int[] channels = getChannelsByAgentType(agentType, agentInfo, worldInfo, scenarioInfo, channelIndex);
        int channel = channels[channelIndex];
        int channelCapacity = scenarioInfo.getCommsChannelBandwidth(channel);

        for (int i = StandardMessagePriority.values().length - 1; i >= 0; i--) {
            for (CommunicationMessage msg : messages) {
                StandardMessage smsg = (StandardMessage) msg;
                if (smsg.getSendingPriority() == StandardMessagePriority.values()[i]) {
                    channelSize[channel - 1] += smsg.getByteArraySize();
                    if (channelSize[channel - 1] > channelCapacity) {
                        channelSize[channel - 1] -= smsg.getByteArraySize();
                        channelIndex++;
                        if (channelIndex < channels.length) {
                            channel = channels[channelIndex];
                            channelCapacity = scenarioInfo.getCommsChannelBandwidth(channel);
                            channelSize[channel - 1] += smsg.getByteArraySize();
                        } else {
                            break;
                        }
                    }
                    channelSendMessageList.get(channel).add(smsg);
                }
            }
        }
    }
}