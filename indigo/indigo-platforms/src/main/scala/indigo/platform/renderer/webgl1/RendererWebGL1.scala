package indigo.platform.renderer.webgl1

import indigo.shared.display.DisplayObject

import indigo.platform.renderer.Renderer
import indigo.shared.platform.RendererConfig
import indigo.shared.datatypes.Matrix4
import indigo.shared.datatypes.RGBA
import indigo.shared.platform.ProcessedSceneData
import indigo.shared.display.DisplayEntity
import indigo.platform.renderer.shared.LoadedTextureAsset
import indigo.platform.renderer.shared.TextureLookupResult
import indigo.platform.renderer.shared.ContextAndCanvas
import indigo.platform.renderer.shared.FrameBufferFunctions
import indigo.platform.renderer.shared.FrameBufferComponents

import scala.collection.mutable

import org.scalajs.dom.html
import org.scalajs.dom.raw.WebGLRenderingContext
import org.scalajs.dom.raw.WebGLProgram
import scala.scalajs.js.typedarray.Float32Array
import org.scalajs.dom.raw.WebGLBuffer
import org.scalajs.dom.raw.WebGLRenderingContext._
import indigo.platform.renderer.shared.RendererHelper
import indigo.platform.renderer.shared.WebGLHelper
import indigo.platform.events.GlobalEventStream
import indigo.shared.events.ViewportResize
import indigo.shared.config.GameViewport

// @SuppressWarnings(Array("org.wartremover.warts.MutableDataStructures"))
final class RendererWebGL1(
    config: RendererConfig,
    loadedTextureAssets: List[LoadedTextureAsset],
    cNc: ContextAndCanvas,
    globalEventStream: GlobalEventStream
) extends Renderer {

  // @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private var resizeRun: Boolean = false
  // @SuppressWarnings(Array("org.wartremover.warts.Var"))
  var lastWidth: Int = 0
  // @SuppressWarnings(Array("org.wartremover.warts.Var"))
  var lastHeight: Int = 0
  // @SuppressWarnings(Array("org.wartremover.warts.Var"))
  var orthographicProjectionMatrix: Matrix4 = Matrix4.identity
  // @SuppressWarnings(Array("org.wartremover.warts.Var"))
  var orthographicProjectionMatrixNoMag: Matrix4 = Matrix4.identity

  def screenWidth: Int  = lastWidth
  def screenHeight: Int = lastWidth

  import indigo.platform.shaders._

  private val gl: WebGLRenderingContext =
    cNc.context

  private val textureLocations: List[TextureLookupResult] =
    loadedTextureAssets.map { li =>
      new TextureLookupResult(li.name, WebGLHelper.organiseImage(gl, li.data))
    }

  private val vertexBuffer: WebGLBuffer = gl.createBuffer()

  private val standardShaderProgram = WebGLHelper.shaderProgramSetup(gl, "Pixel", WebGL1StandardPixelArt)
  private val lightingShaderProgram = WebGLHelper.shaderProgramSetup(gl, "Lighting", WebGL1StandardLightingPixelArt)
  private val mergeShaderProgram    = WebGLHelper.shaderProgramSetup(gl, "Merge", WebGL1StandardMerge)

  // @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private var gameFrameBuffer: FrameBufferComponents.SingleOutput =
    FrameBufferFunctions.createFrameBufferSingle(gl, cNc.canvas.width, cNc.canvas.height)
  // @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private var lightingFrameBuffer: FrameBufferComponents.SingleOutput =
    FrameBufferFunctions.createFrameBufferSingle(gl, cNc.canvas.width, cNc.canvas.height)
  // @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private var uiFrameBuffer: FrameBufferComponents.SingleOutput =
    FrameBufferFunctions.createFrameBufferSingle(gl, cNc.canvas.width, cNc.canvas.height)

  // @SuppressWarnings(Array("org.wartremover.warts.Null"))
  def init(): Unit = {
    // some basic set up.
    gl.disable(DEPTH_TEST)
    gl.viewport(0, 0, gl.drawingBufferWidth.toDouble, gl.drawingBufferHeight.toDouble)
    gl.enable(BLEND)

    // Bind the triangles to the vertex buffer.
    val verticesAndTextureCoords: scalajs.js.Array[Float] = {
      val vert0 = scalajs.js.Array[Float](-0.5f, -0.5f, 0.0f, 1.0f)
      val vert1 = scalajs.js.Array[Float](-0.5f, 0.5f, 0.0f, 0.0f)
      val vert2 = scalajs.js.Array[Float](0.5f, -0.5f, 1.0f, 1.0f)
      val vert3 = scalajs.js.Array[Float](0.5f, 0.5f, 1.0f, 0.0f)

      vert0 ++ vert1 ++ vert2 ++ vert3
    }

    gl.bindBuffer(ARRAY_BUFFER, vertexBuffer)
    gl.bufferData(ARRAY_BUFFER, new Float32Array(verticesAndTextureCoords), STATIC_DRAW)

    gl.useProgram(standardShaderProgram)
    val verticesLocation1 = gl.getAttribLocation(standardShaderProgram, "a_verticesAndCoords")
    RendererFunctions.bindAttibuteBuffer(gl, verticesLocation1, 4)

    gl.useProgram(standardShaderProgram)
    val verticesLocation2 = gl.getAttribLocation(lightingShaderProgram, "a_verticesAndCoords")
    RendererFunctions.bindAttibuteBuffer(gl, verticesLocation2, 4)

    gl.useProgram(standardShaderProgram)
    val verticesLocation3 = gl.getAttribLocation(mergeShaderProgram, "a_verticesAndCoords")
    RendererFunctions.bindAttibuteBuffer(gl, verticesLocation3, 4)

    gl.bindBuffer(ARRAY_BUFFER, null)
  }

  def drawScene(sceneData: ProcessedSceneData): Unit = {

    resize(cNc.canvas, cNc.magnification)

    WebGLHelper.setNormalBlend(gl)
    drawLayer(
      sceneData.gameLayerDisplayObjects,
      Some(gameFrameBuffer),
      RGBA.Black.makeTransparent,
      standardShaderProgram,
      sceneData.gameProjection,
      false
    )

    WebGLHelper.setLightingBlend(gl)
    drawLayer(
      sceneData.lightingLayerDisplayObjects,
      Some(lightingFrameBuffer),
      sceneData.clearColor,
      lightingShaderProgram,
      sceneData.lightingProjection,
      false
    )

    WebGLHelper.setNormalBlend(gl)
    drawLayer(
      sceneData.uiLayerDisplayObjects,
      Some(uiFrameBuffer),
      RGBA.Black.makeTransparent,
      standardShaderProgram,
      sceneData.uiProjection,
      false
    )

    WebGLHelper.setNormalBlend(gl)
    drawLayer(
      mutable.ListBuffer(RendererHelper.screenDisplayObject(lastWidth, lastHeight)),
      None,
      config.clearColor,
      mergeShaderProgram,
      orthographicProjectionMatrixNoMag,
      true
    )
  }

  // @SuppressWarnings(Array("org.wartremover.warts.Null"))
  def drawLayer(
      displayEntities: mutable.ListBuffer[DisplayEntity],
      frameBufferComponents: Option[FrameBufferComponents],
      clearColor: RGBA,
      shaderProgram: WebGLProgram,
      projectionMatrix: Matrix4,
      isMerge: Boolean
  ): Unit = {

    frameBufferComponents match {
      case Some(fb) =>
        FrameBufferFunctions.switchToFramebuffer(gl, fb.frameBuffer, clearColor)

      case None =>
        FrameBufferFunctions.switchToCanvas(gl, config.clearColor)
    }

    gl.useProgram(shaderProgram)

    gl.uniformMatrix4fv(
      location = gl.getUniformLocation(shaderProgram, "u_projection"),
      transpose = false,
      value = RendererHelper.mat4ToJsArray(projectionMatrix)
    )

    // Attribute locations

    // Uniform locations (vertex)
    val transformLocation         = gl.getUniformLocation(shaderProgram, "u_transform")
    val dimensions                = gl.getUniformLocation(shaderProgram, "u_dimensions")
    val rotationAlphaFlipLocation = gl.getUniformLocation(shaderProgram, "u_rotationAlphaFlipHFlipV")
    val frameTransform            = gl.getUniformLocation(shaderProgram, "u_frameTransform")

    // Uniform locations (fragment)
    val tintLocation    = gl.getUniformLocation(shaderProgram, "u_tint")
    val textureLocation = gl.getUniformLocation(shaderProgram, "u_texture")

    // Set once
    gl.uniform1i(textureLocation, 0)

    RendererHelper.sortByDepth(displayEntities).foreach {
      case displayObject: DisplayObject =>
        RendererFunctions.setupVertexShaderState(
          gl,
          displayObject,
          transformLocation,
          dimensions,
          rotationAlphaFlipLocation,
          frameTransform
        )

        if (isMerge)
          RendererFunctions.setupMergeFragmentShaderState(
            gl,
            mergeShaderProgram,
            gameFrameBuffer.diffuse,
            lightingFrameBuffer.diffuse,
            uiFrameBuffer.diffuse
          )
        else {
          textureLocations.find(t => t.name == displayObject.atlasName) match {
            case None =>
              gl.activeTexture(TEXTURE0);
              gl.bindTexture(TEXTURE_2D, null)

            case Some(textureLookup) =>
              gl.activeTexture(TEXTURE0);
              gl.bindTexture(TEXTURE_2D, textureLookup.texture)
          }

          gl.uniform4f(
            tintLocation,
            displayObject.effects.tint(0).toDouble,
            displayObject.effects.tint(1).toDouble,
            displayObject.effects.tint(2).toDouble,
            displayObject.effects.tint(3).toDouble
          )
        }

        gl.drawArrays(TRIANGLE_STRIP, 0, 4)

      case _ =>
        ()
    }

  }

  def resize(canvas: html.Canvas, magnification: Int): Unit = {
    val actualWidth  = canvas.width
    val actualHeight = canvas.height

    if (!resizeRun || (lastWidth != actualWidth) || (lastHeight != actualHeight)) {
      resizeRun = true
      lastWidth = actualWidth
      lastHeight = actualHeight

      orthographicProjectionMatrix = Matrix4.orthographic(actualWidth.toDouble / magnification, actualHeight.toDouble / magnification)
      orthographicProjectionMatrixNoMag = Matrix4.orthographic(actualWidth.toDouble, actualHeight.toDouble)
      // orthographicProjectionMatrixJS = RendererFunctions.mat4ToJsArray(orthographicProjectionMatrix)
      // orthographicProjectionMatrixNoMagJS = RendererFunctions.mat4ToJsArray(Matrix4.orthographic(actualWidth.toDouble, actualHeight.toDouble)).map(_.toFloat)

      gameFrameBuffer = FrameBufferFunctions.createFrameBufferSingle(gl, actualWidth, actualHeight)
      // lightsFrameBuffer = FrameBufferFunctions.createFrameBufferSingle(gl, actualWidth, actualHeight)
      lightingFrameBuffer = FrameBufferFunctions.createFrameBufferSingle(gl, actualWidth, actualHeight)
      // distortionFrameBuffer = FrameBufferFunctions.createFrameBufferSingle(gl, actualWidth, actualHeight)
      uiFrameBuffer = FrameBufferFunctions.createFrameBufferSingle(gl, actualWidth, actualHeight)

      gl.viewport(0, 0, actualWidth.toDouble, actualHeight.toDouble)

      globalEventStream.pushGlobalEvent(ViewportResize(GameViewport(actualWidth, actualHeight)))

      ()
    }
  }

}
