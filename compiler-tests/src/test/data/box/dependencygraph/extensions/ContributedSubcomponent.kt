// ENABLE_DAGGER_INTEROP
// WITH_ANVIL

import com.squareup.anvil.annotations.ContributesSubcomponent
import com.squareup.anvil.annotations.MergeComponent

abstract class SandboxedScope

abstract class SandboxedActivityScope

@SingleIn(SandboxedActivityScope::class)
@ContributesSubcomponent(
  scope = SandboxedActivityScope::class,
  parentScope = SandboxedScope::class,
)
interface SandboxedActivityComponent {
  @GraphExtension.Factory @ContributesTo(SandboxedScope::class)
  @ContributesSubcomponent.Factory
  fun interface Factory {
    fun create(): SandboxedActivityComponent
  }
}

@MergeComponent(scope = SandboxedScope::class)
interface SandboxedComponent {
  fun sandboxedActivityComponentFactory(): SandboxedActivityComponent.Factory
}

fun box(): String {
  val graph = createGraph<SandboxedComponent>()
  val subgraph = graph.sandboxedActivityComponentFactory()
  return "OK"
}
