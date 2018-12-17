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

import android.app.Activity;
import android.support.v4.app.FragmentActivity;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.core.FirestoreClient;
import com.google.firebase.firestore.core.QueryListener;
import com.google.firebase.firestore.core.ViewSnapshot;
import javax.annotation.Nullable;

/** Implements the ListenerRegistration interface by removing a query from the listener. */
public class ListenerRegistrationImpl implements ListenerRegistration {

  private final FirestoreClient client;

  /** The internal query listener object that is used to unlisten from the query. */
  private final QueryListener queryListener;

  /** The event listener for the query that raises events asynchronously. */
  private final ExecutorEventListener<ViewSnapshot> asyncEventListener;

  public static class StopListenerSupportFragment extends android.support.v4.app.Fragment {
    @Override
    public void onStop() {
      super.onStop();
      if (callback != null) {
        callback.run();
      }
    }

    void setCallback(Runnable callback) {
      this.callback = callback;
    }

    private Runnable callback = null;
  }

  public static class StopListenerFragment extends android.app.Fragment {
    @Override
    public void onStop() {
      super.onStop();
      if (callback != null) {
        callback.run();
      }
    }

    void setCallback(Runnable callback) {
      this.callback = callback;
    }

    private Runnable callback = null;
  }

  private void onActivityStopCallOnce(Activity activity, Runnable callback) {
    // Android provides lifecycle callbacks (eg onStop()) that custom `Activity`s can extend. But we
    // can't take advantage of that, since we need to be usable with a generic Activity. So instead,
    // we create a custom Fragment, and add that Fragment to the given Activity. When the Activity
    // stops, it will automatically stop the attached Fragments too.
    //
    // One difficulty with this approach is that how you get a Fragment and attach it to an Activity
    // differs based on the type of Activity. If the Activity is actually a FragmentActivity, then
    // you must use the android.support.v4.app.FragmentManager to do so. Otherwise, you need to use
    // the deprecated android.app.FragmentManager.
    //
    // Possible improvements:
    // 1) Allow other lifecycle callbacks other than just 'onStop'.
    // 2) Use a single fragment for all callbacks. (Here, we create a new fragment per callback.)
    // 3) Use LifecycleOwner (which FragmentActivity implements, but Activity does not) to register
    //    for lifecycle callbacks instead of creating/attaching a Fragment.

    if (activity instanceof FragmentActivity) {
      FragmentActivity fragmentActivity = (FragmentActivity) activity;

      StopListenerSupportFragment f = new StopListenerSupportFragment();
      f.setCallback(callback);
      fragmentActivity
          .getSupportFragmentManager()
          .beginTransaction()
          .add(f, /*tag=*/ "FirestoreOnStopObserverFragment")
          .commitAllowingStateLoss();
    } else {
      // If we get here, then we have a non-FragmentActivity Activity. Unfortunately, all Fragment
      // related classes/methods with non-FragmentActivity Activity's are deprecated, implying that
      // almost everything in this block is deprecated.

      StopListenerFragment f = new StopListenerFragment();
      f.setCallback(callback);
      activity
          .getFragmentManager()
          .beginTransaction()
          .add(f, /*tag=*/ "FirestoreOnStopObserverFragment")
          .commitAllowingStateLoss();
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
      onActivityStopCallOnce(activity, this::remove);
    }
  }

  @Override
  public void remove() {
    asyncEventListener.mute();
    client.stopListening(queryListener);
  }
}
