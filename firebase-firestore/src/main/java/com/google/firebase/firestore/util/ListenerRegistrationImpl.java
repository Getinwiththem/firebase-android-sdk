// Copyright 2018 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.firebase.firestore.util;

import static com.google.firebase.firestore.util.Assert.hardAssert;

import android.app.Activity;
import android.support.v4.app.FragmentActivity;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.core.FirestoreClient;
import com.google.firebase.firestore.core.QueryListener;
import com.google.firebase.firestore.core.ViewSnapshot;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import javax.annotation.Nullable;

/**
 * Implements the ListenerRegistration interface by removing a query from the listener.
 *
 * <p>Regarding activity-scoped listeners, Android provides lifecycle callbacks (eg onStop()) that
 * custom `Activity`s can implement via subclassing. But we can't take advantage of that, since we
 * need to be usable with a generic Activity. So instead, we create a custom Fragment, and add that
 * Fragment to the given Activity. When the Activity stops, it will automatically stop the attached
 * Fragments too.
 *
 * <p>One difficulty with this approach is that how you get a Fragment and attach it to an Activity
 * differs based on the type of Activity. If the Activity is actually a FragmentActivity, then you
 * must use the android.support.v4.app.FragmentManager to do so. Otherwise, you need to use the
 * deprecated android.app.FragmentManager.
 *
 * <p>Possible improvements:
 *
 * <ol>
 *   <li>Allow other lifecycle callbacks other than just 'onStop'.
 *   <li>Use LifecycleOwner (which FragmentActivity implements, but Activity does not) to register
 *       for lifecycle callbacks instead of creating/attaching a Fragment.
 * </ol>
 */
public class ListenerRegistrationImpl implements ListenerRegistration {

  private final FirestoreClient client;

  /** The internal query listener object that is used to unlisten from the query. */
  private final QueryListener queryListener;

  /** The event listener for the query that raises events asynchronously. */
  private final ExecutorEventListener<ViewSnapshot> asyncEventListener;

  static class CallbackList {
    void run() {
      for (Runnable callback : callbacks) {
        if (callback != null) {
          callback.run();
        }
      }
    }

    void add(Runnable callback) {
      callbacks.add(callback);
    }

    private final List<Runnable> callbacks = new ArrayList<>();
  }

  public static class StopListenerSupportFragment extends android.support.v4.app.Fragment {
    @Override
    public void onStop() {
      super.onStop();
      callbacks.run();
    }

    CallbackList callbacks = new CallbackList();
  }

  @SuppressWarnings("deprecation")
  public static class StopListenerFragment extends android.app.Fragment {
    @Override
    public void onStop() {
      super.onStop();
      callbacks.run();
    }

    CallbackList callbacks = new CallbackList();
  }

  @Nullable
  private static <T> T castFragment(Class<T> fragmentClass, @Nullable Object fragment, String tag) {
    try {
      return fragmentClass.cast(fragment);
    } catch (ClassCastException e) {
      throw new IllegalStateException(
          "Fragment with tag '"
              + tag
              + "' is a "
              + fragment.getClass().getName()
              + " but should be a "
              + fragmentClass.getName());
    }
  }

  private static final String SUPPORT_FRAGMENT_TAG = "FirestoreOnStopObserverSupportFragment";
  private static final String FRAGMENT_TAG = "FirestoreOnStopObserverFragment";

  private static WeakHashMap<Activity, WeakReference<StopListenerSupportFragment>>
      supportFragmentByActivity = new WeakHashMap<>();
  private static WeakHashMap<Activity, WeakReference<StopListenerFragment>> fragmentByActivity =
      new WeakHashMap<>();

  /**
   * Implementation for non-FragmentActivity Activity's. Unfortunatly, all Fragment related
   * classes/methods with nonFragmentActivityActivity's are deprecated, imply that almost everything
   * in this function is deprecated.
   */
  @SuppressWarnings("deprecation")
  private void onActivityStopCallOnce(Activity activity, Runnable callback) {
    hardAssert(
        !(activity instanceof FragmentActivity),
        "onActivityStopCallOnce must be called with a *non*-FragmentActivity Activity.");

    synchronized (activity) {
      StopListenerFragment fragment;

      WeakReference<StopListenerFragment> ref = fragmentByActivity.get(activity);
      if (ref != null) {
        fragment = ref.get();
        if (fragment != null) {
          fragment.callbacks.add(callback);
          return;
        }
      }

      fragment =
          castFragment(
              StopListenerFragment.class,
              activity.getFragmentManager().findFragmentByTag(FRAGMENT_TAG),
              FRAGMENT_TAG);

      if (fragment == null || fragment.isRemoving()) {
        fragment = new StopListenerFragment();
        activity
            .getFragmentManager()
            .beginTransaction()
            .add(fragment, FRAGMENT_TAG)
            .commitAllowingStateLoss();
      }
      fragmentByActivity.put(activity, new WeakReference<>(fragment));

      fragment.callbacks.add(callback);
    }
  }

  private void onFragmentActivityStopCallOnce(FragmentActivity activity, Runnable callback) {
    synchronized (activity) {
      StopListenerSupportFragment fragment;

      WeakReference<StopListenerSupportFragment> ref = supportFragmentByActivity.get(activity);
      if (ref != null) {
        fragment = ref.get();
        if (fragment != null) {
          fragment.callbacks.add(callback);
          return;
        }
      }

      fragment =
          castFragment(
              StopListenerSupportFragment.class,
              activity.getSupportFragmentManager().findFragmentByTag(SUPPORT_FRAGMENT_TAG),
              SUPPORT_FRAGMENT_TAG);

      if (fragment == null || fragment.isRemoving()) {
        fragment = new StopListenerSupportFragment();
        activity
            .getSupportFragmentManager()
            .beginTransaction()
            .add(fragment, SUPPORT_FRAGMENT_TAG)
            .commitAllowingStateLoss();
      }
      supportFragmentByActivity.put(activity, new WeakReference<>(fragment));

      fragment.callbacks.add(callback);
    }
  }

  /** Creates a new ListenerRegistration. Is activity-scoped if and only if activity is non-null. */
  public ListenerRegistrationImpl(
      FirestoreClient client,
      QueryListener queryListener,
      @Nullable Activity activity,
      ExecutorEventListener<ViewSnapshot> asyncEventListener) {
    this.client = client;
    this.queryListener = queryListener;
    this.asyncEventListener = asyncEventListener;

    if (activity != null) {
      if (activity instanceof FragmentActivity) {
        onFragmentActivityStopCallOnce((FragmentActivity) activity, this::remove);
      } else {
        onActivityStopCallOnce(activity, this::remove);
      }
    }
  }

  @Override
  public void remove() {
    asyncEventListener.mute();
    client.stopListening(queryListener);
  }
}
