package ZCWL_2026.communication;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import rescuecore2.standard.entities.StandardEntityURN;

import java.util.Arrays;

public class ChannelSubscriber extends adf.core.component.communication.ChannelSubscriber {

    // 强制共享所有无线频道
    private static final boolean FORCE_SHARED_CHANNELS = true;
    // 防止重复订阅的标志
    private boolean subscribed = false;

    // 构造函数
    public ChannelSubscriber() { }

    @Override
    public void subscribe(AgentInfo agentInfo, WorldInfo worldInfo, ScenarioInfo scenarioInfo,
                          MessageManager messageManager) {
        StandardEntityURN agentType = getAgentType(agentInfo, worldInfo);

        // 如果已经订阅过，直接返回
        if (subscribed) {
            //System.err.println("[ChannelSubscriber] 已经订阅过，跳过");
            return;
        }

        int numChannels = scenarioInfo.getCommsChannelsCount() - 1; // 0号是语音频道
        if (FORCE_SHARED_CHANNELS) {
            // 强制订阅所有无线频道
            int[] allChannels = new int[numChannels];
            for (int i = 0; i < numChannels; i++) {
                allChannels[i] = i + 1; // 频道号从1开始
            }
            messageManager.subscribeToChannels(allChannels);
            //System.err.println("[ChannelSubscriber] " + agentType +
              //                 " (共享模式) 订阅所有频道: " + Arrays.toString(allChannels));
        } else {
            // 原有隔离算法
            int maxChannelCount = isPlatoonAgent(agentInfo, worldInfo)
                    ? scenarioInfo.getCommsChannelsMaxPlatoon()
                    : scenarioInfo.getCommsChannelsMaxOffice();
            int[] channels = new int[maxChannelCount];
            for (int i = 0; i < maxChannelCount; i++) {
                channels[i] = getChannelNumber(agentType, i, numChannels);
            }
            messageManager.subscribeToChannels(channels);
            //System.err.println("[ChannelSubscriber] " + agentType +
              //                 " 订阅频道: " + Arrays.toString(channels));
        }

        subscribed = true;
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

    public static int getChannelNumber(StandardEntityURN agentType, int channelIndex, int numChannels) {
        int agentIndex = 0;
        if (agentType == StandardEntityURN.FIRE_BRIGADE || agentType == StandardEntityURN.FIRE_STATION) {
            agentIndex = 1;
        } else if (agentType == StandardEntityURN.POLICE_FORCE || agentType == StandardEntityURN.POLICE_OFFICE) {
            agentIndex = 2;
        } else if (agentType == StandardEntityURN.AMBULANCE_TEAM || agentType == StandardEntityURN.AMBULANCE_CENTRE) {
            agentIndex = 3;
        }
        int index = (3 * channelIndex) + agentIndex;
        if ((index % numChannels) == 0) {
            index = numChannels;
        } else {
            index = index % numChannels;
        }
        return index;
    }
}