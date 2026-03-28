package ZCWL_2026.module.complex;

import adf.core.component.module.algorithm.Clustering;
import adf.core.component.communication.CommunicationMessage;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.information.MessageCivilian;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.util.*;
import static rescuecore2.standard.entities.StandardEntityURN.*;

public class HumanDetector extends adf.core.component.module.complex.HumanDetector {

    private Clustering clustering;
    private EntityID result;
    private MessageManager msgManager;
    private Set<EntityID> knownVictims;
    private Set<EntityID> sentMessages;
    private StandardEntityURN agentType;

    public HumanDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                         ModuleManager mm, DevelopData dd) {
        super(ai, wi, si, mm, dd);
        this.knownVictims = new HashSet<>();
        this.sentMessages = new HashSet<>();
        this.agentType = ai.me().getStandardURN();

        switch (si.getMode()) {
            case PRECOMPUTATION_PHASE:
            case PRECOMPUTED:
            case NON_PRECOMPUTE:
                this.clustering = mm.getModule(
                    "HumanDetector.Clustering",
                    "ZCWL_2026.module.algorithm.SampleKMeans");
                break;
        }
        registerModule(this.clustering);
        
        String name = agentType == FIRE_BRIGADE ? "消防车" : 
                      (agentType == AMBULANCE_TEAM ? "救护车" : "警车");
        System.err.println("[" + name + "] 人员检测器已加载");
    }

    @Override
    public HumanDetector updateInfo(MessageManager mm) {
        super.updateInfo(mm);
        this.msgManager = mm;
        
        for (CommunicationMessage msg : mm.getReceivedMessageList(MessageCivilian.class)) {
            MessageCivilian mc = (MessageCivilian) msg;
            if (mc.isBuriednessDefined() && mc.getBuriedness() > 0) {
                knownVictims.add(mc.getAgentID());
            }
        }
        
        return this;
    }

    @Override
    public HumanDetector calc() {
        // ========== 救护车专用 ==========
        if (this.agentType == AMBULANCE_TEAM) {
            EntityID currentPos = this.agentInfo.getPosition();
            Collection<StandardEntity> entitiesInRange = this.worldInfo.getObjectsInRange(currentPos, 100);
            
            // 检查当前位置是否有可装载的平民
            for (StandardEntity e : entitiesInRange) {
                if (e instanceof Civilian) {
                    Civilian civilian = (Civilian) e;
                    EntityID victimId = civilian.getID();
                    
                    // 平民在当前位置
                    if (civilian.isPositionDefined() && civilian.getPosition().equals(currentPos)) {
                        
                        // 情况1：平民已被挖出（埋压度=0）且有伤害 -> 立即装载
                        if (civilian.isDamageDefined() && civilian.getDamage() > 0 &&
                            (!civilian.isBuriednessDefined() || civilian.getBuriedness() == 0)) {
                            
                            System.err.println("[救护车] 当前位置发现可装载平民: " + victimId + 
                                               " 伤害=" + civilian.getDamage());
                            this.result = victimId;
                            return this;
                        }
                        
                        // 情况2：平民还在掩埋中 -> 忽略，不等待，继续寻找其他目标
                        if (civilian.isBuriednessDefined() && civilian.getBuriedness() > 0) {
                            // 直接忽略，不记录等待
                            continue;
                        }
                    }
                }
            }
        }
        
        // ========== 以下是原有逻辑 ==========
        
        // 检查是否正在搬运伤员
        Human transportHuman = this.agentInfo.someoneOnBoard();
        if (transportHuman != null && agentType == AMBULANCE_TEAM) {
            this.result = transportHuman.getID();
            return this;
        }
        
        // 优先处理已知的被困人员
        if (!knownVictims.isEmpty()) {
            EntityID nearest = findNearestKnownVictim();
            if (nearest != null) {
                this.result = nearest;
                return this;
            }
        }
        
        // 检查当前目标是否仍然有效
        if (this.result != null) {
            Human target = (Human) this.worldInfo.getEntity(this.result);
            if (target == null || (target.isHPDefined() && target.getHP() == 0) ||
                !target.isPositionDefined()) {
                this.result = null;
            }
        }
        
        // 搜索新目标
        if (this.result == null) {
            if (clustering == null) {
                this.result = this.calcTargetInWorld();
            } else {
                this.result = this.calcTargetInCluster(clustering);
                if (this.result == null) {
                    this.result = this.calcTargetInWorld();
                }
            }
        }
        
        // 发送发现消息
        if (this.result != null && this.msgManager != null && !sentMessages.contains(this.result)) {
            Human h = (Human) this.worldInfo.getEntity(this.result);
            if (h instanceof Civilian && h.isBuriednessDefined() && h.getBuriedness() > 0) {
                MessageCivilian msg = new MessageCivilian(true, (Civilian) h);
                this.msgManager.addMessage(msg);
                sentMessages.add(this.result);
            }
        }
        
        return this;
    }

    private EntityID calcTargetInCluster(Clustering clustering) {
        int clusterIndex = clustering.getClusterIndex(this.agentInfo.getID());
        Collection<StandardEntity> elements = clustering.getClusterEntities(clusterIndex);
        if (elements == null || elements.isEmpty()) return null;

        List<Human> targets = new ArrayList<>();
        
        for (StandardEntity next : this.worldInfo.getEntitiesOfType(CIVILIAN)) {
            Human h = (Human) next;
            StandardEntity positionEntity = this.worldInfo.getPosition(h);
            if (positionEntity != null && elements.contains(positionEntity)) {
                if (h.isHPDefined() && h.getHP() > 0) {
                    if (h.isBuriednessDefined() && h.getBuriedness() > 0) {
                        targets.add(h);
                    } else if (agentType == AMBULANCE_TEAM && h.isDamageDefined() && h.getDamage() > 0) {
                        targets.add(h);
                    }
                }
            }
        }
        
        if (!targets.isEmpty()) {
            targets.sort(new DistanceSorter(this.worldInfo, this.agentInfo.me()));
            return targets.get(0).getID();
        }
        return null;
    }

    private EntityID calcTargetInWorld() {
        List<Human> targets = new ArrayList<>();
        
        for (StandardEntity next : this.worldInfo.getEntitiesOfType(CIVILIAN)) {
            Human h = (Human) next;
            StandardEntity positionEntity = this.worldInfo.getPosition(h);
            if (positionEntity != null) {
                if (h.isHPDefined() && h.getHP() > 0) {
                    if (h.isBuriednessDefined() && h.getBuriedness() > 0) {
                        targets.add(h);
                    } else if (agentType == AMBULANCE_TEAM && h.isDamageDefined() && h.getDamage() > 0) {
                        targets.add(h);
                    }
                }
            }
        }
        
        if (!targets.isEmpty()) {
            targets.sort(new DistanceSorter(this.worldInfo, this.agentInfo.me()));
            return targets.get(0).getID();
        }
        return null;
    }

    private EntityID findNearestKnownVictim() {
        EntityID pos = this.agentInfo.getPosition();
        EntityID nearest = null;
        double minDist = Double.MAX_VALUE;
        List<EntityID> toRemove = new ArrayList<>();
        
        for (EntityID vid : knownVictims) {
            Human h = (Human) this.worldInfo.getEntity(vid);
            if (h == null || (h.isHPDefined() && h.getHP() == 0) ||
                (h.isBuriednessDefined() && h.getBuriedness() == 0)) {
                toRemove.add(vid);
                continue;
            }
            if (h.isPositionDefined()) {
                double dist = this.worldInfo.getDistance(pos, h.getPosition());
                if (dist < minDist) {
                    minDist = dist;
                    nearest = vid;
                }
            }
        }
        knownVictims.removeAll(toRemove);
        return nearest;
    }

    @Override
    public EntityID getTarget() { 
        return this.result; 
    }
    
    @Override
    public HumanDetector precompute(PrecomputeData pd) { 
        return this; 
    }
    
    @Override
    public HumanDetector resume(PrecomputeData pd) { 
        return this; 
    }
    
    @Override
    public HumanDetector preparate() { 
        return this; 
    }

    private class DistanceSorter implements Comparator<StandardEntity> {
        private StandardEntity reference;
        private WorldInfo worldInfo;

        DistanceSorter(WorldInfo wi, StandardEntity reference) {
            this.reference = reference;
            this.worldInfo = wi;
        }

        public int compare(StandardEntity a, StandardEntity b) {
            int d1 = this.worldInfo.getDistance(this.reference, a);
            int d2 = this.worldInfo.getDistance(this.reference, b);
            return d1 - d2;
        }
    }
}