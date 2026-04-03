package radiography

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull

/**
 * Structured tree node for agent consumption. Contains only information
 * relevant for UI understanding and interaction — no implementation details.
 */
public class AgentNode(
  public val name: String,
  public val text: String?,
  public val description: String?,
  public val role: String?,
  public val bounds: IntArray?, // [left, top, right, bottom]
  public val clickable: Boolean,
  public val disabled: Boolean,
  public val selected: Boolean,
  public val semanticsNodeId: Int?, // for /action click on Compose nodes
  public val children: List<AgentNode>,
  public val view: View? = null, // for performClick fallback on off-screen Android Views
)

// Node names that are pure wrappers — collapse if they have a single child and no meaningful content.
private val WRAPPER_NAMES = setOf(
  "CompositionLocalProvider",
  "ProvideTextStyle",
  "ProvideContentColorTextStyle",
  "ReusableContentHost",
  "ReusableContent",
  "AnimatedEnterExitImpl",
  "LocalOwnersProvider",
  "ContextMenuArea",
  "SelectionContainer",
  "Box", // often a wrapper
)

// Node names to skip entirely (including their children).
private val SKIP_NAMES = setOf(
  "ViewStub",
  "SvgView",
  "GroupView",
  "PathView",
)

// Subcomposition prefix to strip.
private const val SUBCOMPOSITION_PREFIX = "<subcomposition of "

@OptIn(ExperimentalRadiographyComposeApi::class)
public object AgentTreeBuilder {

  /**
   * Build a structured agent tree from a list of scannable view roots.
   */
  public fun build(
    roots: List<ScannableView>,
    viewFilter: ViewFilter = ViewFilters.NoFilter,
  ): List<AgentNode> {
    return roots
      .filter(viewFilter::matches)
      .mapNotNull { buildNode(it, viewFilter) }
  }

  private fun buildNode(view: ScannableView, viewFilter: ViewFilter): AgentNode? {
    when (view) {
      is ScannableView.ChildRenderingError -> return null

      is ScannableView.AndroidView -> {
        val v = view.view
        if (v.visibility == View.GONE) return null

        val name = view.displayName
        if (name in SKIP_NAMES) return null

        val children = buildChildren(view, viewFilter)

        val text = (v as? TextView)?.text?.toString()?.takeIf { it.isNotEmpty() }
        val desc = v.contentDescription?.toString()?.takeIf { it.isNotEmpty() }
        val clickable = v.isClickable
        val disabled = !v.isEnabled

        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val bounds = intArrayOf(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)

        // Collapse: single-child wrapper with no meaningful content
        if (children.size == 1 && text.isNullOrBlank() && desc.isNullOrEmpty() && !clickable && !disabled) {
          return children[0]
        }

        // Prune: no children, no content, not interactive
        if (children.isEmpty() && text == null && desc == null && !clickable) return null

        return AgentNode(
          name = name,
          text = text,
          description = desc,
          role = null,
          bounds = bounds,
          clickable = clickable,
          disabled = disabled,
          selected = v.isSelected,
          semanticsNodeId = null,
          children = children,
          view = v,
        )
      }

      is ScannableView.ComposeView -> {
        val name = view.displayName
        if (name in SKIP_NAMES) return null

        // Skip unplaced compose nodes (recycled LazyColumn items that aren't visible)
        val layoutInfo = view.semanticsNodes.firstOrNull()?.layoutInfo
        if (layoutInfo != null && !layoutInfo.isPlaced) return null

        val children = buildChildren(view, viewFilter)

        // Subcomposition wrapper — collapse to children
        if (name.startsWith(SUBCOMPOSITION_PREFIX)) {
          return when (children.size) {
            0 -> null
            1 -> children[0]
            else -> {
              // Multiple children from a subcomposition — wrap in parent name
              val parentName = name.removePrefix(SUBCOMPOSITION_PREFIX).removeSuffix(">")
              AgentNode(
                name = parentName,
                text = null, description = null, role = null,
                bounds = boundsOf(view), clickable = false, disabled = false,
                selected = false, semanticsNodeId = null, children = children,
              )
            }
          }
        }

        // Extract semantics
        val configs = view.semanticsConfigurations
        val text = configs.firstNotNullOfOrNull { config ->
          config.getOrNull(SemanticsProperties.Text)
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(" | ") { it.text }
        }
        val desc = configs.firstNotNullOfOrNull { config ->
          config.getOrNull(SemanticsProperties.ContentDescription)
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(", ")
        }
        val role = configs.firstNotNullOfOrNull { config ->
          config.getOrNull(SemanticsProperties.Role)?.let { roleToString(it) }
        }
        val clickable = configs.any { config ->
          config.any { it.key == SemanticsActions.OnClick }
        }
        val disabled = configs.any { config ->
          config.contains(SemanticsProperties.Disabled)
        }
        val selected = configs.any { config ->
          config.getOrNull(SemanticsProperties.Selected) == true
        }
        val semanticsNodeId = view.semanticsNodes.firstOrNull()?.id

        // Collapse: single-child wrapper with no meaningful content
        if (name in WRAPPER_NAMES && children.size == 1
          && text.isNullOrBlank() && desc.isNullOrEmpty() && !clickable && !disabled && !selected
        ) {
          return children[0]
        }

        // Prune: no children, no content, not interactive
        if (children.isEmpty() && text.isNullOrBlank() && desc.isNullOrEmpty() && !clickable && role == null) {
          return null
        }

        return AgentNode(
          name = name,
          text = text,
          description = desc,
          role = role,
          bounds = boundsOf(view),
          clickable = clickable,
          disabled = disabled,
          selected = selected,
          semanticsNodeId = semanticsNodeId,
          children = children,
        )
      }
    }
  }

  private fun buildChildren(view: ScannableView, viewFilter: ViewFilter): List<AgentNode> {
    return try {
      view.children
        .filter(viewFilter::matches)
        .mapNotNull { buildNode(it, viewFilter) }
        .toList()
    } catch (_: Throwable) {
      emptyList()
    }
  }

  private fun boundsOf(view: ScannableView.ComposeView): IntArray {
    val l = view.left
    val t = view.top
    return intArrayOf(l, t, l + view.width, t + view.height)
  }

  private fun roleToString(role: androidx.compose.ui.semantics.Role): String = when (role) {
    androidx.compose.ui.semantics.Role.Button -> "Button"
    androidx.compose.ui.semantics.Role.Checkbox -> "Checkbox"
    androidx.compose.ui.semantics.Role.Switch -> "Switch"
    androidx.compose.ui.semantics.Role.RadioButton -> "RadioButton"
    androidx.compose.ui.semantics.Role.Tab -> "Tab"
    androidx.compose.ui.semantics.Role.Image -> "Image"
    androidx.compose.ui.semantics.Role.DropdownList -> "DropdownList"
    else -> "Unknown"
  }
}
