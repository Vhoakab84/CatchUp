package io.sweers.catchup.rx.boundlifecycle.observers;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import io.reactivex.Observable;
import io.reactivex.ObservableSource;
import io.reactivex.disposables.Disposable;
import io.reactivex.exceptions.CompositeException;
import io.reactivex.exceptions.Exceptions;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;
import io.reactivex.internal.disposables.DisposableHelper;
import io.reactivex.plugins.RxJavaPlugins;
import io.sweers.catchup.rx.boundlifecycle.LifecycleProvider;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import rx.exceptions.OnErrorNotImplementedException;

abstract class BaseObserver implements Disposable {

  private final AtomicReference<Disposable> mainDisposable = new AtomicReference<>();
  private final AtomicReference<Disposable> lifecycleDisposable = new AtomicReference<>();
  protected final Observable<?> lifecycle;
  protected final Consumer<? super Throwable> errorConsumer;

  protected BaseObserver(@NonNull Observable<?> lifecycle,
      @Nullable Consumer<? super Throwable> errorConsumer) {
    this.lifecycle = lifecycle;
    this.errorConsumer = errorConsumer;
  }

  private static <E> Observable<Boolean> mapEvents(@NonNull Observable<E> lifecycle,
      @NonNull Function<E, E> correspondingEvents) {
    return Observable.combineLatest(
        lifecycle.take(1)
            .map(correspondingEvents),
        lifecycle.skip(1),
        (bindUntilEvent, lifecycleEvent) -> lifecycleEvent.equals(bindUntilEvent))
        .filter(b -> b)
        .take(1);
  }

  @SuppressWarnings("unused")
  public final void onSubscribe(Disposable d) {
    if (DisposableHelper.setOnce(this.mainDisposable, d)) {
      DisposableHelper.setOnce(
          this.lifecycleDisposable,
          lifecycle.take(1)
              .subscribe(e -> dispose()));
    }
  }

  @Override
  public final boolean isDisposed() {
    return mainDisposable.get() == DisposableHelper.DISPOSED;
  }

  @Override
  public final void dispose() {
    synchronized (this) {
      DisposableHelper.dispose(lifecycleDisposable);
      DisposableHelper.dispose(mainDisposable);
    }
  }

  public final void onError(Throwable e) {
    dispose();
    if (errorConsumer != null) {
      try {
        errorConsumer.accept(e);
      } catch (Exception e1) {
        Exceptions.throwIfFatal(e1);
        RxJavaPlugins.onError(new CompositeException(e, e1));
      }
    } else {
      throw new OnErrorNotImplementedException(e);
    }
  }

  abstract static class Creator<C extends Creator> {
    protected final Observable<?> lifecycle;
    protected Consumer<? super Throwable> errorConsumer;

    protected <E> Creator(@NonNull LifecycleProvider<E> provider) {
      this.lifecycle = Observable.defer(new Callable<ObservableSource<Boolean>>() {
        @Override
        public ObservableSource<Boolean> call() throws Exception {
          return mapEvents(provider.lifecycle(), provider.correspondingEvents());
        }
      });
    }

    protected Creator(@NonNull Observable<?> lifecycle) {
      this.lifecycle = lifecycle;
    }

    public C onError(@Nullable Consumer<? super Throwable> errorConsumer) {
      this.errorConsumer = errorConsumer;
      return (C) this;
    }

    protected static Consumer<? super Throwable> createTaggedError(@NonNull String tag) {
      return (Consumer<Throwable>) throwable -> {
        throw new OnErrorNotImplementedException(tag, throwable);
      };
    }
  }
}
