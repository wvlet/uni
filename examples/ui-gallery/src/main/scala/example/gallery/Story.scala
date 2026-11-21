package example.gallery

import example.gallery.stories.*
import wvlet.uni.dom.all.*

/**
  * The code of a story, cut from its `// #region` block at build time (project/StoryRegions.scala),
  * with the file and lines it came from.
  */
case class StorySource(code: String, file: String, startLine: Int, endLine: Int):
  def githubUrl: String =
    s"https://github.com/wvlet/uni/blob/main/${file}#L${startLine}-L${endLine}"

/**
  * One demo in the gallery. `id` is both its route (`#/controls/buttons`) and the name of its
  * `// #region` block, so the code panel shows the code that `render` runs.
  */
case class Story(id: String, title: String, summary: String, render: () => RxElement):
  def group: String               = id.takeWhile(_ != '/')
  def source: Option[StorySource] = StorySources.all.get(id)

/** A sidebar section: the stories of one catalog page. */
case class StoryGroup(id: String, title: String, docs: String, stories: Seq[Story])

object Stories:
  val groups: Seq[StoryGroup] = Seq(
    StoryGroup("controls", "Controls", "controls", ControlStories.all),
    StoryGroup("overlays", "Overlays", "overlays", OverlayStories.all),
    StoryGroup("collections", "Collections", "collections", CollectionStories.all),
    StoryGroup("layout", "Layout & Dock", "layout", LayoutStories.all)
  )

  val all: Seq[Story] = groups.flatMap(_.stories)

  def find(id: String): Option[Story] = all.find(_.id == id)

  def groupOf(story: Story): Option[StoryGroup] = groups.find(_.id == story.group)
