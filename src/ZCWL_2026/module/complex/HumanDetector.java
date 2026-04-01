package ZCWL_2026.module.complex;

import adf.core.component.module.algorithm.Clustering;
import adf.core.component.communication.CommunicationMessage;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.information.*;
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

/**
 * 人员检测器 - 只负责检测和报告，不负责分配任务
 */
public class HumanDetector extends adf.core.component.module.complex.HumanDetector {

    private Clustering clustering;
    private EntityID result;
    private MessageManager msgManager;
    private Set<EntityID> reportedVictims;
    private StandardEntityURN agentType;
    private Set<EntityID> knownVictims;

    public HumanDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                         ModuleManager mm, DevelopData dd) {
        super(ai, wi, si, mm, dd);
        this.knownVictims = new HashSet<>();
        this.reportedVictims = new HashSet<>();
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
        
        for (CommunicationMessage msg : mm.getReceivedMessageList()) {
            if (msg instanceof MessageCivilian) {
                MessageCivilian mc = (MessageCivilian) msg;
                if (this.agentType != AMBULANCE_TEAM && mc.isBuriednessDefined() && mc.getBuriedness() > 0) {
                    knownVictims.add(mc.getAgentID());
                }
            } else if (msg instanceof MessagePoliceForce) {
                MessagePoliceForce mpf = (MessagePoliceForce) msg;
                if (this.agentType != AMBULANCE_TEAM && mpf.isBuriednessDefined() && mpf.getBuriedness() > 0) {
                    knownVictims.add(mpf.getAgentID());
                }
            } else if (msg instanceof MessageFireBrigade) {
                MessageFireBrigade mfb = (MessageFireBrigade) msg;
                if (this.agentType != AMBULANCE_TEAM && mfb.isBuriednessDefined() && mfb.getBuriedness() > 0) {
                    knownVictims.add(mfb.getAgentID());
                }
            } else if (msg instanceof MessageAmbulanceTeam) {
                MessageAmbulanceTeam mat = (MessageAmbulanceTeam) msg;
                if (this.agentType != AMBULANCE_TEAM && mat.isBuriednessDefined() && mat.getBuriedness() > 0) {
                    knownVictims.add(mat.getAgentID());
                }
            }
        }
        
        cleanupKnownVictims();
        return this;
    }
    
    private void cleanupKnownVictims() {
        List<EntityID> toRemove = new ArrayList<>();
        for (EntityID victimId : knownVictims) {
            Human victim = (Human) this.worldInfo.getEntity(victimId);
            if (victim == null) {
                toRemove.add(victimId);
            } else if (victim.isHPDefined() && victim.getHP() == 0) {
                toRemove.add(victimId);
            } else if (victim.isBuriednessDefined() && victim.getBuriedness() == 0) {
                toRemove.add(victimId);
            }
        }
        knownVictims.removeAll(toRemove);
    }
    
    /**
     * 检查实体是否有效（位置定义且不为null）
     */
    private boolean isValidEntity(StandardEntity entity) {
        if (entity == null) return false;
        if (entity instanceof Human) {
            Human h = (Human) entity;
            if (!h.isPositionDefined()) return false;
            EntityID pos = h.getPosition();
            if (pos == null) return false;
            StandardEntity posEntity = this.worldInfo.getEntity(pos);
            if (posEntity == null) return false;
        }
        return true;
    }

    @Override
    public HumanDetector calc() {
        this.result = null;
        
        // ========== 救护车专用 ==========
        if (this.agentType == AMBULANCE_TEAM) {
            EntityID currentPos = this.agentInfo.getPosition();
            Collection<StandardEntity> entitiesInRange = this.worldInfo.getObjectsInRange(currentPos, 100);
            
            for (StandardEntity e : entitiesInRange) {
                if (e instanceof Human) {
                    Human human = (Human) e;
                    EntityID humanId = human.getID();
                    
                    // ========== 关键修复：检查位置是否有效 ==========
                    if (!human.isPositionDefined()) {
                        continue;
                    }
                    EntityID humanPos = human.getPosition();
                    if (humanPos == null) {
                        continue;
                    }
                    if (!humanPos.equals(currentPos)) {
                        continue;
                    }
                    
                    boolean isBuried = human.isBuriednessDefined() && human.getBuriedness() > 0;
                    boolean hasDamage = human.isDamageDefined() && human.getDamage() > 0;
                    
                    if (isBuried) {
                        if (!reportedVictims.contains(humanId)) {
                            sendReportMessage(human);
                            reportedVictims.add(humanId);
                        }
                        continue;
                    } else if (hasDamage) {
                        this.result = humanId;
                        return this;
                    }
                }
            }
            
            if (this.result == null && !knownVictims.isEmpty()) {
                EntityID nearest = findNearestKnownVictim();
                if (nearest != null) {
                    this.result = nearest;
                    return this;
                }
            }
        }
        
        // ========== 消防员专用 ==========
        if (this.agentType == FIRE_BRIGADE) {
            if (!knownVictims.isEmpty()) {
                EntityID nearest = findNearestKnownVictim();
                if (nearest != null) {
                    this.result = nearest;
                    return this;
                }
            }
            
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
            
            if (this.result != null && this.msgManager != null && !reportedVictims.contains(this.result)) {
                Human h = (Human) this.worldInfo.getEntity(this.result);
                if (h != null && h.isBuriednessDefined() && h.getBuriedness() > 0) {
                    // ========== 检查位置有效性再发送 ==========
                    if (h.isPositionDefined() && h.getPosition() != null) {
                        sendReportMessage(h);
                        reportedVictims.add(this.result);
                    }
                }
            }
        }
        
        return this;
    }
    
    private void sendReportMessage(Human human) {
        if (this.msgManager == null) return;
        
        // ========== 关键修复：发送前检查位置有效性 ==========
        if (!human.isPositionDefined()) {
            System.err.println("[HumanDetector] 警告: 无法发送报告，平民 " + human.getID() + " 位置未定义");
            return;
        }
        EntityID pos = human.getPosition();
        if (pos == null) {
            System.err.println("[HumanDetector] 警告: 无法发送报告，平民 " + human.getID() + " 位置为 null");
            return;
        }
        
        if (human instanceof Civilian) {
            MessageCivilian msg = new MessageCivilian(true, (Civilian) human);
            this.msgManager.addMessage(msg);
        } else if (human instanceof PoliceForce) {
            MessagePoliceForce msg = new MessagePoliceForce(true, (PoliceForce) human, 
                                                           MessagePoliceForce.ACTION_REST, 
                                                           human.getPosition());
            this.msgManager.addMessage(msg);
        } else if (human instanceof FireBrigade) {
            MessageFireBrigade msg = new MessageFireBrigade(true, (FireBrigade) human, 
                                                            MessageFireBrigade.ACTION_REST, 
                                                            human.getPosition());
            this.msgManager.addMessage(msg);
        } else if (human instanceof AmbulanceTeam) {
            MessageAmbulanceTeam msg = new MessageAmbulanceTeam(true, (AmbulanceTeam) human, 
                                                                MessageAmbulanceTeam.ACTION_REST, 
                                                                human.getPosition());
            this.msgManager.addMessage(msg);
        }
    }
    
    private EntityID calcTargetInCluster(Clustering clustering) {
        int clusterIndex = clustering.getClusterIndex(this.agentInfo.getID());
        Collection<StandardEntity> elements = clustering.getClusterEntities(clusterIndex);
        if (elements == null || elements.isEmpty()) return null;

        List<Human> targets = new ArrayList<>();
        
        for (StandardEntity next : this.worldInfo.getEntitiesOfType(CIVILIAN, POLICE_FORCE, 
                                                                    FIRE_BRIGADE, AMBULANCE_TEAM)) {
            Human h = (Human) next;
            // ========== 检查位置有效性 ==========
            if (!h.isPositionDefined()) continue;
            EntityID hPos = h.getPosition();
            if (hPos == null) continue;
            
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
        
        for (StandardEntity next : this.worldInfo.getEntitiesOfType(CIVILIAN, POLICE_FORCE, 
                                                                    FIRE_BRIGADE, AMBULANCE_TEAM)) {
            Human h = (Human) next;
            // ========== 检查位置有效性 ==========
            if (!h.isPositionDefined()) continue;
            EntityID hPos = h.getPosition();
            if (hPos == null) continue;
            
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
        
        // ========== 检查平民是否还有效 ==========
        if (h == null) {
            toRemove.add(vid);
            continue;
        }
        
        // 已死亡
        if (h.isHPDefined() && h.getHP() == 0) {
            toRemove.add(vid);
            continue;
        }
        
        // 已被救出（埋压度为0）
        if (h.isBuriednessDefined() && h.getBuriedness() == 0) {
            toRemove.add(vid);
            continue;
        }
        
        // ========== 关键修复：检查位置是否有效 ==========
        if (!h.isPositionDefined()) {
            toRemove.add(vid);  // 位置无效，说明已被装载
            System.err.println("[人员检测器] 平民 " + vid + " 位置无效，从列表中移除");
            continue;
        }
        
        EntityID hPos = h.getPosition();
        if (hPos == null) {
            toRemove.add(vid);
            continue;
        }
        
        double dist = this.worldInfo.getDistance(pos, hPos);
        if (dist < minDist) {
            minDist = dist;
            nearest = vid;
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