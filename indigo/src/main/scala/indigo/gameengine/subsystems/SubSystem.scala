package indigo.gameengine.subsystems

import indigo.gameengine.GameTime
import indigo.gameengine.Outcome
import indigo.gameengine.events.GlobalEvent
import indigo.gameengine.scenegraph.SceneUpdateFragment
import indigo.Dice

trait SubSystem {
  type EventType

  val eventFilter: GlobalEvent => Option[EventType]

  def update(gameTime: GameTime, dice: Dice): EventType => Outcome[SubSystem]

  def render(gameTime: GameTime): SceneUpdateFragment

  def report: String
}
