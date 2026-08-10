package mirror.android.content;

import mirror.RefClass;
import mirror.RefObject;

/**
 * 提供 Android {@link android.content.IntentFilter} 私有字段的镜像访问入口。
 *
 * <p>字段使用 {@link Object} 保留平台真实集合类型，以同时兼容旧版 List 和新版 ArraySet。</p>
 */
public class IntentFilter {
    /** Android IntentFilter 的运行时类型。 */
    public static Class TYPE = RefClass.load(IntentFilter.class, android.content.IntentFilter.class);
    /** IntentFilter 内部 action 集合的反射字段。 */
    public static RefObject<Object> mActions;
    /** IntentFilter 内部 category 集合的反射字段。 */
    public static RefObject<Object> mCategories;
}
