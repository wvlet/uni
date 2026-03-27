/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.uni.dom

import org.scalajs.dom
import org.scalajs.dom.{MutationObserver, MutationObserverInit}
import wvlet.uni.rx.{Cancelable, OnError, OnNext, RxOps, RxRunner}
import wvlet.uni.log.LogSupport

import scala.scalajs.js
import scala.util.{Failure, Success, Try}

/**
  * Renders DomNodes to the browser DOM.
  *
  * This renderer composes Cancelable objects to properly clean up resources (event listeners, Rx
  * subscriptions) when elements are removed from the DOM.
  */
object DomRenderer extends LogSupport:

  /**
    * Render a DomNode to a div element with the given id. If the node doesn't exist, creates a new
    * div element with the nodeId.
    *
    * @param nodeId
    *   The id of the target element
    * @param domNode
    *   The node to render
    * @return
    *   A pair of the rendered DOM node and a Cancelable for cleanup
    */
  def renderToNode(nodeId: String, domNode: DomNode): (dom.Node, Cancelable) =
    val node =
      dom.document.getElementById(nodeId) match
        case null =>
          val elem = dom.document.createElement("div")
          elem.setAttribute("id", nodeId)
          dom.document.body.appendChild(elem)
        case other =>
          other
    (node, renderTo(node, domNode))

  /**
    * Convert a DOM node to its HTML string representation.
    */
  def renderToHtml(node: dom.Node): String =
    node match
      case e: dom.Element =>
        e.outerHTML
      case _ =>
        node.innerText

  /**
    * Resolve the effective namespace for an element based on its own namespace and parent context.
    *
    * Rules:
    *   - SVG element always uses SVG namespace
    *   - Children of foreignObject use XHTML namespace
    *   - Elements with default XHTML namespace inherit SVG namespace from SVG parent
    *   - Elements with explicit non-XHTML namespace use their own namespace
    */
  private def resolveNamespace(
      elem: DomElement,
      parentName: String,
      parentNs: DomNamespace
  ): DomNamespace =
    elem.name match
      case "svg" =>
        // SVG element always uses SVG namespace
        DomNamespace.svg
      case _ if parentName == "foreignobject" =>
        // Children of foreignObject use XHTML namespace (lowercased to match getNodeName)
        DomNamespace.xhtml
      case _ if elem.namespace == DomNamespace.xhtml && parentNs == DomNamespace.svg =>
        // Inherit SVG namespace from parent
        DomNamespace.svg
      case _ =>
        elem.namespace

  private def createDomNode(
      e: DomElement,
      parentName: String = "",
      parentNs: DomNamespace = DomNamespace.xhtml
  ): dom.Node =
    val ns = resolveNamespace(e, parentName, parentNs)
    ns match
      case DomNamespace.xhtml =>
        dom.document.createElement(e.name)
      case _ =>
        dom.document.createElementNS(ns.uri, e.name)

  /**
    * Create a new DOM node from the given RxElement.
    *
    * @return
    *   A pair of the rendered DOM node and a Cancelable for cleanup
    */
  def createNode(e: RxElement): (dom.Node, Cancelable) =
    def render(rx: RxElement): (dom.Node, Cancelable) =
      Try(rx.render) match
        case Success(r) =>
          traverse(r)
        case Failure(e) =>
          warn(s"Failed to render ${rx}: ${e.getMessage}", e)
          (dom.document.createElement("span"), Cancelable.empty)

    def traverse(v: Any): (dom.Node, Cancelable) =
      v match
        case h: DomElement =>
          val node       = createDomNode(h)
          val cancelable = h.traverseModifiers(m => renderTo(node, m))
          (node, cancelable)
        case l: LazyRxElement[?] =>
          // Evaluate the lazy value and traverse it directly
          traverse(l.value)
        case Embedded(v) =>
          traverse(v)
        case r: RxElement =>
          r.beforeRender
          val (n, c) = render(r)
          r.onMount(n)
          (n, Cancelable.merge(Cancelable(() => r.beforeUnmount), c))
        case d: dom.Node =>
          (d, Cancelable.empty)
        case other =>
          throw IllegalArgumentException(s"Unsupported top level element: ${other}. Use renderTo")

    traverse(e)
  end createNode

  private def newTextNode(s: String): dom.Text = dom.document.createTextNode(s)

  /**
    * Render a DomNode to an existing DOM node.
    *
    * @param node
    *   The target DOM node
    * @param domNode
    *   The node to render
    * @param modifier
    *   Optional modifier function for the rendered node
    * @return
    *   A Cancelable for cleanup
    */
  def renderTo(
      node: dom.Node,
      domNode: DomNode,
      modifier: dom.Node => dom.Node = identity
  ): Cancelable =
    val context = RenderingContext()
    val c       = renderToInternal(context, node, domNode, modifier)
    context.onFinish()
    c

  /**
    * Collects onRender hooks to call after DOM nodes are mounted.
    */
  private class RenderingContext():
    private var onRenderHooks = List.empty[() => Unit]

    def onFinish(): Unit = onRenderHooks.reverse.foreach(f => f())

    def addOnRenderHook(f: () => Unit): RenderingContext =
      onRenderHooks = f :: onRenderHooks
      this

  /**
    * Get the namespace of a DOM node.
    */
  private def getNodeNamespace(node: dom.Node): DomNamespace =
    node match
      case elem: dom.Element =>
        Option(elem.namespaceURI) match
          case Some(DomNamespace.svg.uri) =>
            DomNamespace.svg
          case Some(DomNamespace.svgXLink.uri) =>
            DomNamespace.svgXLink
          case _ =>
            DomNamespace.xhtml
      case _ =>
        DomNamespace.xhtml

  /**
    * Get the tag name of a DOM node.
    */
  private def getNodeName(node: dom.Node): String =
    node match
      case elem: dom.Element =>
        elem.tagName.toLowerCase
      case _ =>
        ""

  private def renderToInternal(
      context: RenderingContext,
      node: dom.Node,
      domNode: DomNode,
      modifier: dom.Node => dom.Node = identity
  ): Cancelable =
    // Get parent namespace context from the target node
    val parentNs   = getNodeNamespace(node)
    val parentName = getNodeName(node)

    def traverse(v: Any, anchor: Option[dom.Node], localContext: RenderingContext): Cancelable =
      v match
        case DomNode.empty =>
          Cancelable.empty
        case g: DomNodeGroup =>
          val cancelables = g.nodes.map(n => traverse(n, anchor, localContext))
          Cancelable.merge(cancelables)
        case e: DomElement =>
          val elem = createDomNode(e, parentName, parentNs)
          val c    = e.traverseModifiers(m => renderToInternal(localContext, elem, m))
          node.mountHere(elem, anchor)
          c
        case rx: RxOps[?] =>
          val (start, end) = node.createMountSection()
          var c1           = Cancelable.empty
          val c2           =
            RxRunner.runContinuously(rx) { ev =>
              node.clearMountSection(start, end)
              c1.cancel
              ev match
                case OnNext(value) =>
                  val ctx = RenderingContext()
                  c1 = traverse(value, Some(start), ctx)
                  ctx.onFinish()
                case OnError(e) =>
                  warn(s"Error while rendering ${rx}: ${e.getMessage}", e)
                  c1 = Cancelable.empty
                case _ =>
                  c1 = Cancelable.empty
            }
          Cancelable.merge(c1, c2)
        case a: DomAttribute =>
          addAttribute(node, a)
        case vb: ValueBinding =>
          handleValueBinding(node, vb)
        case cb: CheckedBinding =>
          handleCheckedBinding(node, cb)
        // IntersectionObserver bindings
        case ib: IntersectionBinding =>
          handleIntersectionBinding(node, ib)
        case ieb: IntersectionEntryBinding =>
          handleIntersectionEntryBinding(node, ieb)
        case iob: IntersectionOnceBinding =>
          handleIntersectionOnceBinding(node, iob)
        // ResizeObserver bindings
        case rb: ResizeBinding =>
          handleResizeBinding(node, rb)
        case reb: ResizeEntryBinding =>
          handleResizeEntryBinding(node, reb)
        case rbd: ResizeBindingDebounced =>
          handleResizeBindingDebounced(node, rbd)
        // Click outside detection
        case cb: ClickOutsideBinding =>
          handleClickOutsideBinding(node, cb)
        // Ref bindings
        case rb: RefBinding[?] =>
          node match
            case elem: dom.Element =>
              rb.ref.asInstanceOf[DomRef[dom.Element]].set(elem)
              Cancelable(() => rb.ref.asInstanceOf[DomRef[dom.Element]].clear())
            case _ =>
              Cancelable.empty
        // Portal nodes
        case pn: PortalNode =>
          handlePortal(pn.targetId, pn.children)
        case pb: PortalToBody =>
          handlePortalToBody(pb.children)
        case pe: PortalToElement =>
          handlePortalToElement(pe.target, pe.children)
        case n: dom.Node =>
          node.mountHere(n, anchor)
          Cancelable.empty
        case e: Embedded =>
          traverse(e.v, anchor, localContext)
        case rx: RxElement =>
          rx.beforeRender
          Try(rx.render) match
            case Success(r) =>
              val c1   = renderToInternal(localContext, node, r)
              val elem = node.lastChild
              val c2   = rx.traverseModifiers(m => renderToInternal(localContext, elem, m))
              if rx.onMount ne RxElement.NoOp then
                val observer: MutationObserver = MutationObserver { (mut, obs) =>
                  mut.foreach { m =>
                    m.addedNodes
                      .find(_ eq elem)
                      .foreach { n =>
                        elem match
                          case e: dom.Element if Option(e.id).exists(_.nonEmpty) =>
                            val elementId = e.id
                            Option(dom.document.getElementById(elementId)) match
                              case Some(_) =>
                                rx.onMount(elem)
                              case None =>
                                dom
                                  .window
                                  .setTimeout(
                                    () =>
                                      if Option(dom.document.getElementById(elementId)).isDefined
                                      then
                                        rx.onMount(elem),
                                    0
                                  )
                          case _ =>
                            // No ID or not an element, call onMount directly
                            rx.onMount(elem)
                      }
                  }
                  obs.disconnect()
                }
                observer.observe(
                  node,
                  new MutationObserverInit:
                    attributes = node.nodeType == dom.Node.ATTRIBUTE_NODE
                    childList = node.nodeType != dom.Node.ATTRIBUTE_NODE
                )
              end if
              node.mountHere(elem, anchor)
              Cancelable.merge(Cancelable(() => rx.beforeUnmount), Cancelable.merge(c1, c2))
            case Failure(e) =>
              warn(s"Failed to render ${rx}: ${e.getMessage}", e)
              Cancelable(() => rx.beforeUnmount)
          end match
        case s: String =>
          val textNode = newTextNode(s)
          node.mountHere(textNode, anchor)
          Cancelable.empty
        case r: RawHtml =>
          val domNode = dom.document.createElement("span")
          domNode.innerHTML = r.html
          // Mount all child nodes, not just the first one
          domNode.childNodes.foreach(n => node.mountHere(n, anchor))
          Cancelable.empty
        case EntityRef(entityName) =>
          val domNode = dom.document.createElement("span")
          var entity  = entityName.trim
          if !entity.startsWith("&") then
            entity = s"&${entity}"
          if !entity.endsWith(";") then
            entity = s"${entity};"
          domNode.innerHTML = entity
          node.mountHere(domNode, anchor)
          Cancelable.empty
        case v: Int =>
          node.mountHere(newTextNode(v.toString), anchor)
          Cancelable.empty
        case v: Long =>
          node.mountHere(newTextNode(v.toString), anchor)
          Cancelable.empty
        case v: Float =>
          node.mountHere(newTextNode(v.toString), anchor)
          Cancelable.empty
        case v: Double =>
          node.mountHere(newTextNode(v.toString), anchor)
          Cancelable.empty
        case v: Char =>
          node.mountHere(newTextNode(v.toString), anchor)
          Cancelable.empty
        case b: Boolean =>
          node.mountHere(newTextNode(b.toString), anchor)
          Cancelable.empty
        case None =>
          Cancelable.empty
        case Some(x) =>
          traverse(x, anchor, localContext)
        case s: Iterable[?] =>
          val cancelables =
            for el <- s
            yield traverse(el, anchor, localContext)
          Cancelable.merge(cancelables)
        case other =>
          throw IllegalArgumentException(s"Unsupported type: ${other}")

    traverse(domNode, anchor = None, context)
  end renderToInternal

  private def removeStringFromAttributeValue(attrValue: String, toRemove: String): String =
    if attrValue == null then
      ""
    else
      attrValue.split("""\s+""").filter(x => x != toRemove).mkString(" ")

  private def removeStyleValue(styleValue: String, toRemove: String): String =
    if styleValue == null then
      ""
    else
      import scala.util.chaining.*
      val targetValueToRemove = toRemove.trim
      styleValue
        .trim
        .split(""";\s*""")
        .map(x => s"${x};")
        .filter(x => x != targetValueToRemove)
        .mkString("; ")
        .pipe(x =>
          if x.nonEmpty then
            s"${x};"
          else
            x
        )

  private def addAttribute(node: dom.Node, a: DomAttribute): Cancelable =
    val htmlNode = node.asInstanceOf[dom.HTMLElement]

    def traverse(v: Any): Cancelable =
      v match
        case null | None | false =>
          // For property-backed boolean attributes, also set the property to false
          a.name match
            case "checked" | "disabled" | "selected" =>
              setDomProperty(node, a.name, false)
            case "value" =>
              setDomProperty(node, "value", "")
            case _ =>
              ()
          a.ns match
            case DomNamespace.xhtml =>
              htmlNode.removeAttribute(a.name)
            case ns =>
              htmlNode.removeAttributeNS(ns.uri, a.name)
          Cancelable.empty
        case Some(x) =>
          traverse(x)
        case rx: RxOps[?] =>
          var c1 = Cancelable.empty
          val c2 =
            RxRunner.runContinuously(rx) { ev =>
              c1.cancel
              ev match
                case OnNext(value) =>
                  c1 = traverse(value)
                case OnError(e) =>
                  warn(s"Error while rendering ${rx}: ${e.getMessage}", e)
                  c1 = Cancelable.empty
                case _ =>
                  c1 = Cancelable.empty
            }
          Cancelable.merge(c1, c2)
        case f: Function0[?] =>
          node.setEventListener(a.name, (_: dom.Event) => f())
        case f: Function1[dom.Node @unchecked, ?] =>
          node.setEventListener(a.name, f)
        case _ =>
          val value =
            v match
              case true =>
                ""
              case _ =>
                v.toString
          a.name match
            case "style" =>
              val prev = htmlNode.style.cssText
              if prev.nonEmpty && a.append && value.nonEmpty then
                htmlNode.style.cssText = s"${prev} ${value}"
              else
                htmlNode.style.cssText = value
              Cancelable { () =>
                if htmlNode != null && a.append && value.nonEmpty then
                  val newAttributeValue = removeStyleValue(htmlNode.style.cssText, value)
                  htmlNode.style.cssText = newAttributeValue
              }
            // DOM properties that need direct property assignment
            case "value" =>
              setDomProperty(node, "value", v)
              Cancelable.empty
            case "checked" | "disabled" | "selected" =>
              setDomProperty(node, a.name, v)
              Cancelable.empty
            case _ =>
              def removeAttribute(): Unit =
                a.ns match
                  case DomNamespace.xhtml =>
                    htmlNode.removeAttribute(a.name)
                  case ns =>
                    htmlNode.removeAttributeNS(ns.uri, a.name)

              def setAttribute(newAttrValue: String): Unit =
                a.ns match
                  case DomNamespace.xhtml =>
                    htmlNode.setAttribute(a.name, newAttrValue)
                  case ns =>
                    htmlNode.setAttributeNS(ns.uri, a.name, newAttrValue)

              val newAttrValue =
                if a.append && htmlNode.hasAttribute(a.name) then
                  s"${htmlNode.getAttribute(a.name)} ${value}"
                else
                  value
              setAttribute(newAttrValue)
              Cancelable { () =>
                if htmlNode != null && a.append && htmlNode.hasAttribute(a.name) then
                  val v = htmlNode.getAttribute(a.name)
                  if v != null then
                    val newAttrValue = removeStringFromAttributeValue(v, value)
                    removeAttribute()
                    if newAttrValue.nonEmpty then
                      setAttribute(newAttrValue)
              }
          end match

    traverse(a.v)
  end addAttribute

  /**
    * Set a DOM property directly on an element. Properties are different from attributes - they
    * reflect the current state of the element (e.g., input.value, checkbox.checked).
    */
  private def setDomProperty(node: dom.Node, name: String, value: Any): Unit =
    val dyn = node.asInstanceOf[js.Dynamic]
    value match
      case null | None | false =>
        name match
          case "value" =>
            dyn.updateDynamic("value")("")
          case "checked" | "disabled" =>
            dyn.updateDynamic(name)(false)
          case "selected" =>
            dyn.updateDynamic(name)(false)
          case _ =>
            ()
      case true =>
        name match
          case "checked" | "disabled" | "selected" =>
            dyn.updateDynamic(name)(true)
          case _ =>
            dyn.updateDynamic(name)(true)
      case Some(v) =>
        setDomProperty(node, name, v)
      case s: String =>
        dyn.updateDynamic(name)(s)
      case other =>
        dyn.updateDynamic(name)(other.toString)

  /**
    * Handle two-way binding for string values (text inputs, textareas, selects).
    */
  private def handleValueBinding(node: dom.Node, binding: ValueBinding): Cancelable =
    val dyn      = node.asInstanceOf[js.Dynamic]
    val variable = binding.variable

    // Guard flag to prevent infinite loops
    var isUpdating = false

    // 1. Subscribe to RxVar changes -> update DOM property
    val rxCancelable =
      RxRunner.runContinuously(variable) { ev =>
        ev match
          case OnNext(newValue: String @unchecked) =>
            if !isUpdating then
              isUpdating = true
              dyn.value = newValue
              isUpdating = false
          case _ =>
            ()
      }

    // 2. Listen to DOM events -> update RxVar
    val eventName =
      if binding.useChangeEvent then
        "onchange"
      else
        "oninput"
    val listener =
      (e: dom.Event) =>
        if !isUpdating then
          isUpdating = true
          val domValue = dyn.value.asInstanceOf[String]
          if domValue != variable.get then
            variable := domValue
          isUpdating = false
    dyn.updateDynamic(eventName)(listener)

    Cancelable.merge(rxCancelable, Cancelable(() => dyn.updateDynamic(eventName)(null)))
  end handleValueBinding

  /**
    * Handle two-way binding for boolean values (checkboxes).
    */
  private def handleCheckedBinding(node: dom.Node, binding: CheckedBinding): Cancelable =
    val dyn      = node.asInstanceOf[js.Dynamic]
    val variable = binding.variable

    // Guard flag to prevent infinite loops
    var isUpdating = false

    // 1. Subscribe to RxVar changes -> update DOM property
    val rxCancelable =
      RxRunner.runContinuously(variable) { ev =>
        ev match
          case OnNext(newValue: Boolean @unchecked) =>
            if !isUpdating then
              isUpdating = true
              dyn.checked = newValue
              isUpdating = false
          case _ =>
            ()
      }

    // 2. Listen to DOM change event -> update RxVar
    val listener =
      (e: dom.Event) =>
        if !isUpdating then
          isUpdating = true
          val domValue = dyn.checked.asInstanceOf[Boolean]
          if domValue != variable.get then
            variable := domValue
          isUpdating = false
    dyn.updateDynamic("onchange")(listener)

    Cancelable.merge(rxCancelable, Cancelable(() => dyn.updateDynamic("onchange")(null)))
  end handleCheckedBinding

  /**
    * Handle IntersectionObserver binding that updates a boolean RxVar.
    */
  private def handleIntersectionBinding(node: dom.Node, binding: IntersectionBinding): Cancelable =
    val elem     = node.asInstanceOf[dom.Element]
    val variable = binding.target
    val config   = binding.config

    val observer = IntersectionObserver(
      { (entries, _) =>
        entries
          .headOption
          .foreach { entry =>
            variable := entry.isIntersecting
          }
      },
      IntersectionObserverInit(config)
    )
    observer.observe(elem)
    Cancelable(() => observer.disconnect())
  end handleIntersectionBinding

  /**
    * Handle IntersectionObserver binding that updates an RxVar with full entry details.
    */
  private def handleIntersectionEntryBinding(
      node: dom.Node,
      binding: IntersectionEntryBinding
  ): Cancelable =
    val elem     = node.asInstanceOf[dom.Element]
    val variable = binding.target
    val config   = binding.config

    val observer = IntersectionObserver(
      { (entries, _) =>
        entries
          .headOption
          .foreach { entry =>
            variable :=
              Some(
                IntersectionEntry(
                  isIntersecting = entry.isIntersecting,
                  intersectionRatio = entry.intersectionRatio,
                  boundingClientRect = entry.boundingClientRect,
                  rootBounds = Option(entry.rootBounds),
                  target = entry.target
                )
              )
          }
      },
      IntersectionObserverInit(config)
    )
    observer.observe(elem)
    Cancelable(() => observer.disconnect())
  end handleIntersectionEntryBinding

  /**
    * Handle IntersectionObserver binding that calls a callback once when visible.
    */
  private def handleIntersectionOnceBinding(
      node: dom.Node,
      binding: IntersectionOnceBinding
  ): Cancelable =
    val elem     = node.asInstanceOf[dom.Element]
    val callback = binding.callback
    val config   = binding.config

    lazy val observer: IntersectionObserver = IntersectionObserver(
      { (entries, _) =>
        entries
          .headOption
          .foreach { entry =>
            if entry.isIntersecting then
              callback()
              observer.disconnect()
          }
      },
      IntersectionObserverInit(config)
    )
    observer.observe(elem)
    Cancelable(() => observer.disconnect())
  end handleIntersectionOnceBinding

  /**
    * Handle ResizeObserver binding that updates an RxVar with (width, height).
    */
  private def handleResizeBinding(node: dom.Node, binding: ResizeBinding): Cancelable =
    val elem     = node.asInstanceOf[dom.Element]
    val variable = binding.target

    val observer = ResizeObserver { (entries, _) =>
      entries
        .headOption
        .foreach { entry =>
          val rect = entry.contentRect
          variable := (rect.width, rect.height)
        }
    }
    observer.observe(elem)
    Cancelable(() => observer.disconnect())
  end handleResizeBinding

  /**
    * Handle ResizeObserver binding that updates an RxVar with full entry details.
    */
  private def handleResizeEntryBinding(node: dom.Node, binding: ResizeEntryBinding): Cancelable =
    val elem     = node.asInstanceOf[dom.Element]
    val variable = binding.target

    val observer = ResizeObserver { (entries, _) =>
      entries
        .headOption
        .foreach { entry =>
          variable := Some(ResizeEntry(contentRect = entry.contentRect, target = entry.target))
        }
    }
    observer.observe(elem)
    Cancelable(() => observer.disconnect())
  end handleResizeEntryBinding

  /**
    * Handle ResizeObserver binding with debouncing.
    */
  private def handleResizeBindingDebounced(
      node: dom.Node,
      binding: ResizeBindingDebounced
  ): Cancelable =
    val elem       = node.asInstanceOf[dom.Element]
    val variable   = binding.target
    val debounceMs = binding.debounceMs

    var timeoutId: js.UndefOr[Int] = js.undefined

    val observer = ResizeObserver { (entries, _) =>
      entries
        .headOption
        .foreach { entry =>
          timeoutId.foreach(id => dom.window.clearTimeout(id))
          timeoutId = dom
            .window
            .setTimeout(
              () =>
                val rect = entry.contentRect
                variable := (rect.width, rect.height)
              ,
              debounceMs
            )
        }
    }
    observer.observe(elem)
    Cancelable { () =>
      timeoutId.foreach(id => dom.window.clearTimeout(id))
      observer.disconnect()
    }
  end handleResizeBindingDebounced

  /**
    * Handle click outside detection by registering a document-level mousedown listener.
    *
    * Uses setTimeout(0) to avoid catching the opening click that triggered this binding.
    */
  private def handleClickOutsideBinding(node: dom.Node, binding: ClickOutsideBinding): Cancelable =
    val elem     = node.asInstanceOf[dom.Element]
    val callback = binding.callback

    var listener: js.UndefOr[js.Function1[dom.Event, Unit]] = js.undefined

    val setupId = dom
      .window
      .setTimeout(
        () =>
          val handler: js.Function1[dom.Event, Unit] =
            (event: dom.Event) =>
              event match
                case me: dom.MouseEvent =>
                  val target = me.target.asInstanceOf[dom.Node]
                  if !elem.contains(target) then
                    callback(me)
                case _ =>
                  ()
          listener = handler
          dom.document.addEventListener("mousedown", handler)
        ,
        0
      )

    Cancelable { () =>
      dom.window.clearTimeout(setupId)
      listener.foreach(l => dom.document.removeEventListener("mousedown", l))
    }
  end handleClickOutsideBinding

  /**
    * Handle Portal rendering to a target element by ID.
    */
  private def handlePortal(targetId: String, children: Seq[DomNode]): Cancelable =
    val target =
      dom.document.getElementById(targetId) match
        case null =>
          val elem = dom.document.createElement("div")
          elem.setAttribute("id", targetId)
          dom.document.body.appendChild(elem)
          elem
        case existing =>
          existing

    // Create a container for this portal instance
    val container = dom.document.createElement("div")
    target.appendChild(container)

    // Render children into container
    val cancelables = children.map(child => renderTo(container, child))

    Cancelable.merge(Cancelable.merge(cancelables), Cancelable(() => target.removeChild(container)))
  end handlePortal

  /**
    * Handle Portal rendering to document.body.
    */
  private def handlePortalToBody(children: Seq[DomNode]): Cancelable =
    // Create a container for this portal instance
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container)

    // Render children into container
    val cancelables = children.map(child => renderTo(container, child))

    Cancelable.merge(
      Cancelable.merge(cancelables),
      Cancelable(() => dom.document.body.removeChild(container))
    )
  end handlePortalToBody

  /**
    * Handle Portal rendering to a specific element.
    */
  private def handlePortalToElement(target: dom.Element, children: Seq[DomNode]): Cancelable =
    // Create a container for this portal instance
    val container = dom.document.createElement("div")
    target.appendChild(container)

    // Render children into container
    val cancelables = children.map(child => renderTo(container, child))

    Cancelable.merge(Cancelable.merge(cancelables), Cancelable(() => target.removeChild(container)))
  end handlePortalToElement

  extension (node: dom.Node)
    private def eval(v: Any): Cancelable =
      v match
        case rx: RxOps[?] =>
          rx.run(_ => ())
        case Some(v) =>
          eval(v)
        case s: Iterable[?] =>
          val cancellables =
            for x <- s
            yield eval(x)
          Cancelable.merge(cancellables)
        case _ =>
          Cancelable.empty

    def setEventListener[A, U](key: String, listener: A => U): Cancelable =
      val dyn         = node.asInstanceOf[js.Dynamic]
      var c1          = Cancelable.empty
      val newListener =
        (e: A) =>
          c1.cancel
          c1 = eval(listener(e))
      dyn.updateDynamic(key)(newListener)
      Cancelable { () =>
        c1.cancel
        dyn.updateDynamic(key)(null)
      }

    def createMountSection(): (dom.Node, dom.Node) =
      val start = newTextNode("")
      val end   = newTextNode("")
      node.appendChild(end)
      node.appendChild(start)
      (start, end)

    def mountHere(child: dom.Node, start: Option[dom.Node]): Unit =
      start.fold(node.appendChild(child))(point => node.insertBefore(child, point))
      ()

    def clearMountSection(start: dom.Node, end: dom.Node): Unit =
      val next = start.previousSibling
      if next != end then
        node.removeChild(next)
        clearMountSection(start, end)

  end extension

end DomRenderer
