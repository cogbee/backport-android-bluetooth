package backport.android.bluetooth;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

abstract class DelegateFactory {

	private static final class InvocationHandlerImpl implements
			InvocationHandler {
		final Object mDelegate;
		final Class<?> mClass;
		final Map<Method, Method> mMethodCache;

		InvocationHandlerImpl(Class<?> receiver, Class<?> delegateClass,
				Object delegateInstance) {
			mDelegate = delegateInstance;
			mClass = delegateClass;
			HashMap<Method, Method> map = new HashMap<Method, Method>();
			mMethodCache = Collections.synchronizedMap(map);
		}

		public final Object invoke(Object target, Method method, Object[] args)
				throws Throwable {
			Method delegateMethod = getMethodFromCache(method);

			if (!hasDelegateMethod(delegateMethod)) {
				return null;
			}

			Object receiver = null;
			boolean staticMethod = isMethodStatic(delegateMethod);

			if (!staticMethod) {
				receiver = mDelegate;
			}

			boolean invoke = canInvoke(staticMethod, receiver);

			if (!invoke) {
				return null;
			}

			Object res = delegateMethod.invoke(receiver, args);
			return res;
		}

		private Method getMethodFromCache(Method method)
				throws SecurityException {
			Method delegate;

			if (mMethodCache.containsKey(method)) {
				delegate = mMethodCache.get(method);
				return delegate;
			}

			delegate = getDelegateMethod(method, mClass);
			makeAccessible(delegate);
			mMethodCache.put(method, delegate);
			return delegate;
		}

	}

	static final boolean canInvoke(boolean staticMethod, Object receiver) {
		if (staticMethod) {
			return true;
		}

		boolean b = receiver != null;
		return b;
	}

	static final <T> T create(Class<T> type, Class<?> delegateClass,
			Object delegateInstance) {
		if (type == null) {
			throw new IllegalArgumentException("type must not be null");
		}

		if (delegateClass == null) {
			throw new IllegalArgumentException("delegateClass must not be null");
		}

		InvocationHandler handler = new InvocationHandlerImpl(type,
				delegateClass, delegateInstance);
		ClassLoader classLoader = type.getClassLoader();
		Class<?>[] types = new Class[] { type };
		Object proxy = Proxy.newProxyInstance(classLoader, types, handler);
		T t = type.cast(proxy);
		return t;
	}

	static final <T> T create(Class<T> type, Object delegate) {
		if (delegate == null) {
			throw new IllegalArgumentException("delegate must not be null");
		}

		T t = create(type, delegate.getClass(), delegate);
		return t;
	}

	static final <T> T create(Class<T> type, String delegateClassName,
			Object delegate) throws ClassNotFoundException {
		if (delegateClassName == null) {
			throw new IllegalArgumentException(
					"delegateClassName must not be null");
		}

		ClassLoader classLoader = type.getClassLoader();
		Class<?> delegateClass = classLoader.loadClass(delegateClassName);
		T t = create(type, delegateClass, delegate);
		return t;
	}

	static final Method getDeclaredMethod(Class<?> type, String name,
			Class<?>[] params) {
		for (Class<?> c = type; c != null; c = c.getSuperclass()) {

			Method[] methods = c.getDeclaredMethods();
			Method m = getMostMatchMethod(name, params, methods);
			boolean b = m != null;

			if (b) {
				return m;
			}
		}

		return null;
	}

	static final Method getDelegateMethod(Method method, Class<?> clazz)
			throws SecurityException {
		String name = method.getName();
		Class<?>[] params = method.getParameterTypes();
		Method delegateMethod;

		try {
			delegateMethod = clazz.getMethod(name, params);
			return delegateMethod;
		} catch (NoSuchMethodException e) {
		}

		delegateMethod = getDeclaredMethod(clazz, name, params);
		return delegateMethod;
	}

	static final Method getMostMatchMethod(String name, Class<?>[] params,
			Method[] methods) {
		for (Method m : methods) {
			boolean b = isSignatureMatches(m, name, params);

			if (b) {
				return m;
			}
		}

		return null;
	}

	static final boolean hasDelegateMethod(Method m) {
		return m != null;
	}

	static final boolean isMethodStatic(Method m) {
		int modifiers = m.getModifiers();
		boolean b = Modifier.isStatic(modifiers);
		return b;
	}

	static final boolean isSignatureMatches(Method m, String name,
			Class<?>[] params) {
		boolean b;
		String n = m.getName();
		b = n.equals(name);

		if (!b) {
			return false;
		}

		Class<?>[] p = m.getParameterTypes();
		b = Arrays.equals(p, params);
		return b;
	}

	static final void makeAccessible(Method m) {
		if (m == null) {
			return;
		}

		if (m.isAccessible()) {
			return;
		}

		m.setAccessible(true);
	}
}
