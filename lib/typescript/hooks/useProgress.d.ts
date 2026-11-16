export interface ProgressState {
    /** Seconds from start of the current track. 0 when no track active. */
    position: number;
    /** Seconds. 0 when duration is unknown or no track active. */
    duration: number;
    /** Seconds buffered ahead of `position`. 0 when unknown. */
    buffered: number;
}
/**
 * Subscribes to native `onProgress` updates. Progress is driven by a native
 * periodic timer (see NOTES.md §5), so this hook stays accurate even when
 * the app is backgrounded.
 */
export declare function useProgress(): ProgressState;
//# sourceMappingURL=useProgress.d.ts.map