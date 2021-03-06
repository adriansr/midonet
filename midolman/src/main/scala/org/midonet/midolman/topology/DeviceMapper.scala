/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.midolman.topology

import java.util.UUID

import javax.annotation.Nullable

import scala.collection.mutable

import com.google.protobuf.Message

import rx.Observable.OnSubscribe
import rx.observers.Subscribers
import rx.subjects.{BehaviorSubject, PublishSubject}
import rx.{Observable, Observer, Subscriber}

import org.midonet.cluster.data.ZoomConvert.fromProto
import org.midonet.cluster.data.ZoomObject
import org.midonet.cluster.data.storage.NotFoundException
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.topology.DeviceMapper._
import org.midonet.midolman.topology.VirtualTopology.{Device, Key}
import org.midonet.util.functors._
import org.midonet.util.logging.Logger

object DeviceMapper {

    final val MapperClosedException =
        new IllegalStateException("Device mapper is closed")

    /**
      * Ignores the errors emitted by a device if the flag for a specific
      * exception is set. Otherwise, the error is emitted.
      */
    final def handleException(id: UUID, ignoreNotFound: Boolean, log: Logger) = {
        makeFunc1 { e: Throwable =>
            e match {
                case nfe: NotFoundException if ignoreNotFound =>
                    log.debug("Device {} not found: ignoring", id)
                    Observable.empty()
                case _ =>
                    log.warn("Device {} error", id, e)
                    Observable.error(e)
            }
        }
    }

    /**
      * The state of the mapper subscription to the underlying storage
      * observables.
      */
    private[topology] object MapperState extends Enumeration {
        class MapperState(val isTerminal: Boolean) extends Val
        /** The mapper is not subscribed to the storage observable. */
        val Unsubscribed = new MapperState(false)
        /** The mapper is subscribed and the observable is not in a terminal
          * state. */
        val Subscribed = new MapperState(false)
        /** The mapper has completed, usually indicating that the corresponding
          * device was deleted. It is possible to create a new mapper, but the
          * new mapper may other complete again or emit an error (if the device
          * does not exist). */
        val Completed = new MapperState(true)
        /** The mapper has emitted an error, indicating a problem with the
          * underlying storage observable or an internal mapper error. It is
          * possible to create a new mapper for the same device, but the error
          * may be emitted again (e.g. ZK not connected). */
        val Error = new MapperState(true)
        /** The mapper was closed, but it possible to create a new one for the
          * same device. */
        val Closed = new MapperState(true)
    }

    /**
     * Abstract base class for a device state, either based on the virtual
     * topology or the storage backend.
     */
    protected[topology] class DeviceState[T >: Null <: AnyRef]
                                         (id: UUID, source: Observable[T]) {
        protected var currentDevice: T = null
        protected val mark = PublishSubject.create[T]

        /** The device observable, notifications on the VT thread. */
        def observable: Observable[T] = source
            .doOnNext(makeAction1(currentDevice = _))
            .doOnCompleted(makeAction0(() => currentDevice = null))
            .takeUntil(mark)

        /** Completes the observable corresponding to this device state. */
        def complete() = mark.onCompleted()
        /** Gets the current device or null, if none is set. */
        @Nullable
        def device: T = currentDevice
        /** Indicates whether the device state has received the device data */
        def isReady: Boolean = currentDevice ne null
    }

}

/**
 * The base class for a device observable [[OnSubscribe]] handler. The call()
 * method of this class is called for every subscriber of the enclosing
 * [[rx.Observable]], and it connects the [[rx.Subscriber]] with underlying
 * observable exposed by the observable() method, generating device
 * updates for a specific device type.
 *
 * The class also implements the [[rx.Observer]] interface, to add the following
 * custom actions into the update stream, before any subscriber receives
 * the notification:
 *  - update the [[VirtualTopology]] device cache
 *  - remove the enclosing observable from the [[VirtualTopology]] observable
 *    map, when the update stream receives a onCompleted or onError notification
 *
 * The device mapper isolates the underlying observable and subscribes with
 * a [[BehaviorSubject]] funnel, which ensures that:
 *  - all subscribers correspond to a single subscription to storage
 *  - the [[DeviceMapper]] observer can execute the custom actions before
 *    subscribers are notified.
 */
abstract class DeviceMapper[D <: Device](val clazz: Class[D], val id: UUID,
                                         val vt: VirtualTopology)
    extends OnSubscribe[D] with Observer[D] with MidolmanLogging {

    import DeviceMapper.MapperClosedException

    private final val key = Key(clazz, id)
    private final var state = MapperState.Unsubscribed
    private final val cache = BehaviorSubject.create[D]()
    private final val subscriber = Subscribers.from(cache)

    @volatile private var error: Throwable = null

    private val timestamp = System.nanoTime()
    private var initialized = false

    /**
     * An implementing class must override this method, which is called
     * whenever the device observable receives a new subscriber.
     *
     * It is recommended that the access to the storage layer is handled by this
     * method, such that a subscription to storage is not created until the
     * device observable receives at least one subscriber.
     */
    protected def observable: Observable[D]

    override final def call(child: Subscriber[_ >: D]): Unit =
        vt.vtExecutor.submit(makeRunnable {
            if (!handleSubscriptionIfTerminal(child)) {
                if (state == MapperState.Unsubscribed) {
                    state = MapperState.Subscribed
                    observable.doOnEach(this).subscribe(subscriber)
                }
                cache subscribe child
            }
        })

    override final def onCompleted() = {
        assertThread()
        log.debug(s"Device ${clazz.getSimpleName}:$id deleted")
        state = MapperState.Completed
        val device = vt.devices.remove(id)
        vt.observables.remove(key)

        if ((device ne null) && device.getClass == clazz) {
            onDeviceChanged(device.asInstanceOf[D])
        }

        vt.metrics.deviceComplete(clazz)
        vt.metrics.deviceLifetime(clazz, System.nanoTime() - timestamp)
    }

    override final def onError(e: Throwable) = {
        assertThread()
        e match {
            case nfe: NotFoundException =>
                log.debug(s"Device ${clazz.getSimpleName}:$id not found")
            case _ =>
                log.warn(s"Device ${clazz.getSimpleName}:$id error", e)
        }


        error = e
        state = MapperState.Error
        val device = vt.devices.remove(id)
        vt.observables.remove(key)

        if ((device ne null) && device.getClass == clazz) {
            onDeviceChanged(device.asInstanceOf[D])
        }

        vt.metrics.deviceError(clazz)
        vt.metrics.deviceLifetime(clazz, System.nanoTime() - timestamp)
    }

    override final def onNext(device: D) = {
        assertThread()
        log.debug(s"Device ${clazz.getSimpleName}:$id updated: $device")
        vt.devices.put(id, device)
        vt.metrics.deviceUpdate(clazz)
        if (!initialized) {
            initialized = true
            vt.metrics.deviceLatency(clazz, System.nanoTime() - timestamp)
        }
        onDeviceChanged(device)
    }

    protected def onDeviceChanged(device: D): Unit = {}

    /**
     * Checks that this method is executed on the same thread as the one used
     * during the initialization of the mapper.
     */
    @throws[DeviceMapperException]
    @inline protected def assertThread(): Unit = vt.assertThread()

    /**
     * Synchronize devices with the new list of deviceIds. Complete and remove
     * the device state for any devices whose IDs are not in deviceIds, and
     * create, add to devices, and publish to devicesObserver a new DeviceState
     * for any IDs not already in devices.
     *
     * @param deviceIds New list of device IDs.
     * @param devices Current map of device IDs to device states.
     * @param devicesObserver Observer for publishing device observables.
     * @tparam T Device type.
     */
    protected def updateDeviceState[T >: Null <: AnyRef](
            deviceIds: Set[UUID], devices: mutable.Map[UUID, DeviceState[T]],
            devicesObserver: Observer[Observable[T]])
            (stateFactory: (UUID) => DeviceState[T]): Unit = {
        // Complete and remove observables for devices no longer needed.
        for ((id, state) <- devices.toList if !deviceIds.contains(id)) {
            state.complete()
            devices -= id
        }

        // Create state for new devices, and publish their observables to the
        // aggregate observer.
        val addedDevices = new mutable.MutableList[DeviceState[T]]
        for (id <- deviceIds if !devices.contains(id)) {
            val state = stateFactory(id)
            devices += id -> state
            addedDevices += state
        }
        for (deviceState <- addedDevices) {
            devicesObserver onNext deviceState.observable
        }
    }

    /**
     * The same as `updateDeviceState` for devices fetched from the virtual
     * topology. The method takes two `onCompleted` and `onError` functions,
     * which are called when the observable for a device completes or emits
     * an error. In the latter case, the function may return a recovery
     * observable.
     */
    protected def updateTopologyDeviceState[T >: Null <: Device](
            clazz: Class[T],
            deviceIds: Set[UUID],
            devices: mutable.Map[UUID, DeviceState[T]],
            devicesObserver: Observer[Observable[T]],
            onCompleted: (UUID) => Unit = _ => { },
            onError: (UUID, Throwable) => Observable[T] =
                (id: UUID, e: Throwable) => Observable.error[T](e)): Unit = {
        updateDeviceState(deviceIds, devices, devicesObserver) { id =>
            new DeviceState[T](id, VirtualTopology
                .observable[T](clazz, id)
                .doOnCompleted(makeAction0 { onCompleted(id) })
                .onErrorResumeNext(makeFunc1 { e: Throwable => onError(id, e) }))
        }
    }

    /**
     * The same as `updateDeviceState` for devices fetched from the ZOOM
     * store.
     */
    protected def updateZoomDeviceState[T >: Null <: ZoomObject, U <: Message](
            tClass: Class[T], uClass: Class[U],
            deviceIds: Set[UUID], devices: mutable.Map[UUID, DeviceState[T]],
            devicesObserver: Observer[Observable[T]], vt: VirtualTopology): Unit = {
        updateDeviceState(deviceIds, devices, devicesObserver) { id =>
            new DeviceState[T](id, vt.store
                .observable(uClass, id)
                .distinctUntilChanged()
                .observeOn(vt.vtScheduler)
                .map[T](makeFunc1(fromProto[T, U](_, tClass))))
        }
    }

    /**
     * Completes the device state for all devices in the given map.
     */
    protected def completeDeviceState[T >: Null <: AnyRef](
        devices: mutable.Map[UUID, DeviceState[T]]): Unit = {
        for (state <- devices.values) {
            state.complete()
        }
        devices.clear()
    }

    /** Handles the subscription when the mapper is in a terminal state, and
      * returns `true` if the mapper was in a terminal state. */
    private def handleSubscriptionIfTerminal(child: Subscriber[_ >: D])
    : Boolean = {
        if (state == MapperState.Completed) {
            child.onCompleted()
            return true
        }
        if (state == MapperState.Error) {
            child onError error
            return true
        }
        if (state == MapperState.Closed) {
            child onError MapperClosedException
            return true
        }
        false
    }

}

class DeviceMapperException(msg: String) extends Exception(msg) {
    def this(clazz: Class[_], id: UUID) =
        this(s"Device mapper exception for device ${clazz.getSimpleName}:$id")
    def this(clazz: Class[_], id: UUID, msg: String) =
        this(s"Device mapper exception for device ${clazz.getSimpleName}:$id" +
             s": $msg")
}
