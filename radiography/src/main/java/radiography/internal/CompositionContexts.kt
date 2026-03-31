package radiography.internal

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionContext
import androidx.compose.ui.tooling.data.Group
import androidx.compose.ui.tooling.data.UiToolingDataApi
import kotlin.LazyThreadSafetyMode.PUBLICATION

private val REFLECTION_CONSTANTS by lazy(PUBLICATION) {
  try {
    // In Compose 1.7+, ReusableRememberObserverHolder was renamed to RememberObserverHolder.
    // Try both names for backward compatibility.
    val rememberObserverHolderClass = try {
      Class.forName("androidx.compose.runtime.RememberObserverHolder")
    } catch (_: ClassNotFoundException) {
      Class.forName("androidx.compose.runtime.ReusableRememberObserverHolder")
    }

    object {
      val CompositionContextHolderClass =
        Class.forName("androidx.compose.runtime.ComposerImpl\$CompositionContextHolder")
      val CompositionContextImplClass =
        Class.forName("androidx.compose.runtime.ComposerImpl\$CompositionContextImpl")
      val RememberObserverHolderClass = rememberObserverHolderClass
      val CompositionContextHolderRefField =
        CompositionContextHolderClass.getDeclaredField("ref")
          .apply { isAccessible = true }
      val CompositionContextImplComposersField =
        CompositionContextImplClass.getDeclaredField("composers")
          .apply { isAccessible = true }
    }
  } catch (e: Throwable) {
    null
  }
}

@OptIn(UiToolingDataApi::class)
internal fun Group.getCompositionContexts(): Sequence<CompositionContext> {
  return REFLECTION_CONSTANTS?.run {
    // In Compose 1.7+, CompositionContextHolder may appear directly in Group.data
    // (not wrapped in RememberObserverHolder), or wrapped in the new RememberObserverHolder.
    val directContexts = data.asSequence()
      .filter { it != null && CompositionContextHolderClass.isInstance(it) }
      .mapNotNull { holder ->
        try {
          CompositionContextHolderRefField.get(holder) as? CompositionContext
        } catch (_: Throwable) {
          null
        }
      }

    val wrappedContexts = data.asSequence()
      .filter { it != null && it::class.java == RememberObserverHolderClass }
      .mapNotNull { holder ->
        try {
          val wrapped = holder?.let { holder::class.java.getMethod("getWrapped") }?.invoke(holder)
          wrapped?.tryGetCompositionContext()
        } catch (_: Throwable) {
          null
        }
      }

    directContexts + wrappedContexts
  } ?: emptySequence()
}

@Suppress("UNCHECKED_CAST")
internal fun CompositionContext.tryGetComposers(): Iterable<Composer> {
  return REFLECTION_CONSTANTS?.let {
    if (!it.CompositionContextImplClass.isInstance(this)) return emptyList()
    it.CompositionContextImplComposersField.get(this) as? Iterable<Composer>
  } ?: emptyList()
}

private fun Any?.tryGetCompositionContext() = REFLECTION_CONSTANTS?.let {
  if (this == null || !it.CompositionContextHolderClass.isInstance(this)) return@let null
  it.CompositionContextHolderRefField.get(this) as? CompositionContext
}
