import Foundation
import NitroModules

extension TrackPlayer {
  /// True when the caller is already executing on `playerQueue`.
  ///
  /// `DispatchQueue.sync` runs its body on the calling thread, so a thread
  /// identity test answers the wrong question — it reports "main" for work
  /// serialised on the queue and would let a nested `sync` deadlock. The
  /// queue's own specific travels with the execution context instead.
  internal var isOnPlayerQueue: Bool {
    DispatchQueue.getSpecific(key: playerQueueKey) == ObjectIdentifier(self)
  }

  /// Claim the audio session, then start the engine.
  ///
  /// Every path that starts playback goes through here so activation cannot be
  /// forgotten at a new one. `activateIfNeeded` is idempotent, so the cost
  /// after the first play is a bool check.
  ///
  /// Activation is deliberately not done at `configure()`: that would stop
  /// whatever else the user is listening to the moment a consumer app
  /// launches, before they have pressed anything.
  internal func beginPlayback() {
    audioSession.activateIfNeeded()
    engine?.play()
  }

  /// Enqueue `body` on `playerQueue` **at call time**, on the calling thread,
  /// and resolve the returned promise when it completes.
  ///
  /// This is what makes the queue's FIFO order equal the JS call order.
  /// `Promise.async` starts an unstructured `Task`, and two Tasks created back
  /// to back have no ordering relationship with each other — so a body that
  /// hops onto the queue *inside* the Task reaches the player at an arbitrary
  /// later point. Two calls issued in order from JS could then arrive
  /// reversed: `play()` then `pause()` landing as pause-then-play, leaving
  /// audio running after the user paused it.
  ///
  /// A serial queue gives mutual exclusion, not ordering. Ordering comes from
  /// when the work is enqueued, which is why this runs before returning.
  internal func enqueue(_ body: @escaping () -> Void) -> Promise<Void> {
    let promise = Promise<Void>()
    playerQueue.async {
      body()
      promise.resolve()
    }
    return promise
  }

  /// Enqueue a mutation at call time and hand its result to a receiver mirror
  /// that runs off the queue.
  ///
  /// The enqueue is what fixes ordering against other player work — a later
  /// call cannot overtake this one. Doing it with `async` rather than `sync`
  /// matters just as much: the mutation can rebuild the whole player queue,
  /// and `sync` would run that on the JS thread.
  ///
  /// `mirror` awaits the receiver afterwards, holding neither the queue nor
  /// the calling thread, and is chained behind the previous mutation's mirror
  /// so the receiver sees mutations in the order they were made. The promise
  /// resolves once the mirror returns, so a consumer awaiting the call knows
  /// local and remote are both settled.
  internal func enqueueThenMirror<T>(
    _ body: @escaping () -> T,
    mirror: @escaping (T, _ generation: UInt64) async -> Void
  ) -> Promise<Void> {
    let promise = Promise<Void>()
    playerQueue.async {
      self.queueMutationGeneration &+= 1
      let generation = self.queueMutationGeneration
      let value = body()
      let previous = self.castMirrorChain
      self.castMirrorChain = Task {
        // Deltas are only correct against a receiver that has applied
        // everything before them, so this waits rather than racing. A failed
        // mirror still completes its Task, so one bad round-trip cannot wedge
        // the chain.
        await previous?.value
        await mirror(value, generation)
        promise.resolve()
      }
    }
    return promise
  }

  /// Value-returning variant of `enqueue`.
  internal func enqueueValue<T>(_ body: @escaping () -> T) -> Promise<T> {
    let promise = Promise<T>()
    playerQueue.async {
      promise.resolve(withResult: body())
    }
    return promise
  }

  /// Throwing variant of `enqueue` — a thrown error rejects the JS promise
  /// with its message, as `Promise.async` does for a throwing body.
  internal func enqueueThrowing(_ body: @escaping () throws -> Void) -> Promise<Void> {
    let promise = Promise<Void>()
    playerQueue.async {
      do {
        try body()
        promise.resolve()
      } catch {
        promise.reject(withError: error)
      }
    }
    return promise
  }

  /// Serialise a mutation body on `playerQueue` and return a
  /// Promise-compatible awaitable. Centralising here keeps every access to
  /// player state on one queue, which is what makes the state safe to read
  /// without locks inside these bodies.
  internal func onPlayerQueue(_ body: @escaping () -> Void) async {
    await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
      playerQueue.async {
        body()
        cont.resume()
      }
    }
  }

  /// Run `body` on `playerQueue` synchronously and return its value. Used by
  /// the Nitro getters and the listener register / dispose paths, which are
  /// not Promise-wrapped and run on whatever thread the JS bridge uses.
  ///
  /// Running inline when already on the queue is what makes re-entrant calls
  /// safe: `playerQueue.sync` from a block already on `playerQueue` is an
  /// immediate deadlock.
  internal func onPlayerQueueSync<T>(_ body: () -> T) -> T {
    if isOnPlayerQueue { return body() }
    return playerQueue.sync(execute: body)
  }
}
