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

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

public class MessageTool {
    private ScenarioInfo scenarioInfo;
    private DevelopData developData;
    private Set<EntityID> sentRequestMessages;
    private Set<EntityID> sentInformationMessages;

    public MessageTool(ScenarioInfo scenarioInfo, DevelopData developData) {
        this.scenarioInfo = scenarioInfo;
        this.developData = developData;
        this.sentRequestMessages = new HashSet<>();
        this.sentInformationMessages = new HashSet<>();
    }

    public void reflectMessage(AgentInfo agentInfo, WorldInfo worldInfo,
                               ScenarioInfo scenarioInfo, MessageManager messageManager) {
        // 实现消息反射逻辑
    }

    public void sendRequestMessages(AgentInfo agentInfo, WorldInfo worldInfo,
                                    ScenarioInfo scenarioInfo, MessageManager messageManager) {
        // 实现发送请求消息逻辑
    }

    public void sendInformationMessages(AgentInfo agentInfo, WorldInfo worldInfo,
                                        ScenarioInfo scenarioInfo, MessageManager messageManager) {
        // 实现发送信息消息逻辑
    }

    public void reset() {
        this.sentRequestMessages.clear();
        this.sentInformationMessages.clear();
    }
}

