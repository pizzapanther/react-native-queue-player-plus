import type { CacheStatus, LookaheadCacheConfig } from '../types';
export interface UseLookaheadCacheResult {
    status: CacheStatus;
    setConfig: (config: LookaheadCacheConfig) => Promise<void>;
    clear: () => Promise<void>;
}
/**
 * Subscribes to lookahead-cache status updates and exposes imperative
 * setters for runtime reconfiguration (e.g. switch from wifi → cellular
 * and drop the lookahead count).
 */
export declare function useLookaheadCache(): UseLookaheadCacheResult;
//# sourceMappingURL=useLookaheadCache.d.ts.map