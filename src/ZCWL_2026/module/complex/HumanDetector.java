package ZCWL_2026.module.complex;

import adf.core.component.module.algorithm.Clustering;
import adf.core.component.communication.CommunicationMessage;
import adf.core.agent.communication.MessageManager;
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
  private EntityID   result;

  public HumanDetector( AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData ) {
    super( ai, wi, si, moduleManager, developData );

    this.result = null;

    switch ( si.getMode() ) {
    case PRECOMPUTATION_PHASE:
        this.clustering = moduleManager.getModule(
            "HumanDetector.Clustering",
            "ZCWL_2026.module.algorithm.AmbulanceClustering" );
        break;
    case PRECOMPUTED:
        this.clustering = moduleManager.getModule(
            "HumanDetector.Clustering",
            "ZCWL_2026.module.algorithm.AmbulanceClustering" );
        break;
    case NON_PRECOMPUTE:
        this.clustering = moduleManager.getModule(
            "HumanDetector.Clustering",
            "ZCWL_2026.module.algorithm.AmbulanceClustering" );
        break;
}
registerModule( this.clustering );
  }

  @Override
  public HumanDetector updateInfo( MessageManager messageManager ) {
    super.updateInfo( messageManager );
    if ( this.getCountUpdateInfo() > 1 ) {
      return this;
    }
    return this;
  }

  @Override
  public HumanDetector calc() {
    // 检查是否正在搬运伤员
    Human transportHuman = this.agentInfo.someoneOnBoard();
    if ( transportHuman != null ) {
      this.result = transportHuman.getID();
      return this;
    }
    
    // 检查当前目标是否仍然有效
    if ( this.result != null ) {
      Human target = (Human) this.worldInfo.getEntity( this.result );
      if ( target != null ) {
        if ( !target.isHPDefined() || target.getHP() == 0 ) {
          this.result = null;
        } else if ( !target.isPositionDefined() ) {
          this.result = null;
        } else {
          StandardEntity position = this.worldInfo.getPosition( target );
          if ( position != null ) {
            StandardEntityURN positionURN = position.getStandardURN();
            if ( positionURN == REFUGE || positionURN == AMBULANCE_TEAM ) {
              this.result = null;
            }
          }
        }
      }
    }
    
    // 如果没有目标，寻找新目标
    if ( this.result == null ) {
      if ( clustering == null ) {
        this.result = this.calcTargetInWorld();
        return this;
      }
      this.result = this.calcTargetInCluster( clustering );
      if ( this.result == null ) {
        this.result = this.calcTargetInWorld();
      }
    }
    return this;
  }

  private EntityID calcTargetInCluster( Clustering clustering ) {
    int clusterIndex = clustering.getClusterIndex( this.agentInfo.getID() );
    Collection<StandardEntity> elements = clustering.getClusterEntities( clusterIndex );
    
    if ( elements == null || elements.isEmpty() ) {
      return null;
    }

    List<Human> rescueTargets = new ArrayList<>();
    List<Human> loadTargets = new ArrayList<>();
    
    // 检查其他救援人员（救护车、消防员、警察）
    for ( StandardEntity next : this.worldInfo.getEntitiesOfType( AMBULANCE_TEAM, FIRE_BRIGADE, POLICE_FORCE ) ) {
      Human h = (Human) next;
      if ( this.agentInfo.getID().getValue() == h.getID().getValue() ) {
        continue;
      }
      
      StandardEntity positionEntity = this.worldInfo.getPosition( h );
      if ( positionEntity != null && (elements.contains( positionEntity ) || elements.contains( h )) ) {
        if ( h.isHPDefined() && h.isBuriednessDefined() && h.getHP() > 0 && h.getBuriedness() > 0 ) {
          rescueTargets.add( h );
        }
      }
    }
    
    // 检查平民
    for ( StandardEntity next : this.worldInfo.getEntitiesOfType( CIVILIAN ) ) {
      Human h = (Human) next;
      StandardEntity positionEntity = this.worldInfo.getPosition( h );
      
      if ( positionEntity != null && positionEntity instanceof Area ) {
        if ( elements.contains( positionEntity ) ) {
          if ( h.isHPDefined() && h.getHP() > 0 ) {
            if ( h.isBuriednessDefined() && h.getBuriedness() > 0 ) {
              rescueTargets.add( h );
            } else {
              if ( h.isDamageDefined() && h.getDamage() > 0 && positionEntity.getStandardURN() != REFUGE ) {
                loadTargets.add( h );
              }
            }
          }
        }
      }
    }
    
    // 优先返回需要救援的目标，其次是需要装载的目标
    if ( rescueTargets.size() > 0 ) {
      rescueTargets.sort( new DistanceSorter( this.worldInfo, this.agentInfo.me() ) );
      return rescueTargets.get( 0 ).getID();
    }
    if ( loadTargets.size() > 0 ) {
      loadTargets.sort( new DistanceSorter( this.worldInfo, this.agentInfo.me() ) );
      return loadTargets.get( 0 ).getID();
    }
    
    return null;
  }

  private EntityID calcTargetInWorld() {
    List<Human> rescueTargets = new ArrayList<>();
    List<Human> loadTargets = new ArrayList<>();
    
    // 检查其他救援人员
    for ( StandardEntity next : this.worldInfo.getEntitiesOfType( AMBULANCE_TEAM, FIRE_BRIGADE, POLICE_FORCE ) ) {
      Human h = (Human) next;
      if ( this.agentInfo.getID().getValue() != h.getID().getValue() ) {
        StandardEntity positionEntity = this.worldInfo.getPosition( h );
        if ( positionEntity != null && h.isHPDefined() && h.isBuriednessDefined() ) {
          if ( h.getHP() > 0 && h.getBuriedness() > 0 ) {
            rescueTargets.add( h );
          }
        }
      }
    }
    
    // 检查平民
    for ( StandardEntity next : this.worldInfo.getEntitiesOfType( CIVILIAN ) ) {
      Human h = (Human) next;
      StandardEntity positionEntity = this.worldInfo.getPosition( h );
      
      if ( positionEntity != null && positionEntity instanceof Area ) {
        if ( h.isHPDefined() && h.getHP() > 0 ) {
          if ( h.isBuriednessDefined() && h.getBuriedness() > 0 ) {
            rescueTargets.add( h );
          } else {
            if ( h.isDamageDefined() && h.getDamage() > 0 && positionEntity.getStandardURN() != REFUGE ) {
              loadTargets.add( h );
            }
          }
        }
      }
    }
    
    // 优先返回需要救援的目标，其次是需要装载的目标
    if ( rescueTargets.size() > 0 ) {
      rescueTargets.sort( new DistanceSorter( this.worldInfo, this.agentInfo.me() ) );
      return rescueTargets.get( 0 ).getID();
    }
    if ( loadTargets.size() > 0 ) {
      loadTargets.sort( new DistanceSorter( this.worldInfo, this.agentInfo.me() ) );
      return loadTargets.get( 0 ).getID();
    }
    
    return null;
  }

  @Override
  public EntityID getTarget() {
    return this.result;
  }

  @Override
  public HumanDetector precompute( PrecomputeData precomputeData ) {
    super.precompute( precomputeData );
    if ( this.getCountPrecompute() >= 2 ) {
      return this;
    }
    return this;
  }

  @Override
  public HumanDetector resume( PrecomputeData precomputeData ) {
    super.resume( precomputeData );
    if ( this.getCountResume() >= 2 ) {
      return this;
    }
    return this;
  }

  @Override
  public HumanDetector preparate() {
    super.preparate();
    if ( this.getCountPreparate() >= 2 ) {
      return this;
    }
    return this;
  }

  /**
   * 距离排序器 - 按与参考实体的距离升序排列
   */
  private class DistanceSorter implements Comparator<StandardEntity> {
    private StandardEntity reference;
    private WorldInfo      worldInfo;

    DistanceSorter( WorldInfo wi, StandardEntity reference ) {
      this.reference = reference;
      this.worldInfo = wi;
    }

    public int compare( StandardEntity a, StandardEntity b ) {
      int d1 = this.worldInfo.getDistance( this.reference, a );
      int d2 = this.worldInfo.getDistance( this.reference, b );
      return d1 - d2;
    }
  }
}
