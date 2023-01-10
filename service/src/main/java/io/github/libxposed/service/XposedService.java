package io.github.libxposed.service;

import static android.os.ParcelFileDescriptor.MODE_READ_ONLY;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@SuppressWarnings("unused")
public final class XposedService {

    public final static class ServiceException extends RuntimeException {
        private ServiceException(RemoteException e) {
            super("Xposed service error", e);
        }
    }

    public enum Privilege {
        /**
         * Unknown privilege value
         */
        FRAMEWORK_PRIVILEGE_UNKNOWN,

        /**
         * The framework is running as root
         */
        FRAMEWORK_PRIVILEGE_ROOT,

        /**
         * The framework is running in a container with a fake system_server
         */
        FRAMEWORK_PRIVILEGE_CONTAINER,

        /**
         * The framework is running as a different app, which may have at most shell permission
         */
        FRAMEWORK_PRIVILEGE_APP,

        /**
         * The framework is embedded in the hooked app, which means {@link #getRemotePreferences} and remote file streams will be null
         */
        FRAMEWORK_PRIVILEGE_EMBEDDED
    }

    private final IXposedService mService;
    private final Map<String, RemotePreferences> mRemotePrefs = new HashMap<>();

    final ReentrantReadWriteLock deletionLock = new ReentrantReadWriteLock();

    XposedService(IXposedService service) {
        mService = service;
    }

    IXposedService getRaw() {
        return mService;
    }

    /**
     * Get the Xposed API version of current implementation
     *
     * @return API version
     * @throws ServiceException If the service is dead or error occurred
     */
    public int getAPIVersion() {
        try {
            return mService.getAPIVersion();
        } catch (RemoteException e) {
            throw new ServiceException(e);
        }
    }

    /**
     * Get the Xposed framework name of current implementation
     *
     * @return Framework name
     * @throws ServiceException If the service is dead or error occurred
     */
    @NonNull
    public String getFrameworkName() {
        try {
            return mService.getFrameworkName();
        } catch (RemoteException e) {
            throw new ServiceException(e);
        }
    }

    /**
     * Get the Xposed framework version of current implementation
     *
     * @return Framework version
     * @throws ServiceException If the service is dead or error occurred
     */
    @NonNull
    public String getFrameworkVersion() {
        try {
            return mService.getFrameworkVersion();
        } catch (RemoteException e) {
            throw new ServiceException(e);
        }
    }

    /**
     * Get the Xposed framework version code of current implementation
     *
     * @return Framework version code
     * @throws ServiceException If the service is dead or error occurred
     */
    public long getFrameworkVersionCode() {
        try {
            return mService.getFrameworkVersionCode();
        } catch (RemoteException e) {
            throw new ServiceException(e);
        }
    }

    /**
     * Get the Xposed framework privilege of current implementation
     *
     * @return Framework privilege
     * @throws ServiceException If the service is dead or error occurred
     */
    @NonNull
    public Privilege getFrameworkPrivilege() {
        try {
            int value = mService.getFrameworkPrivilege();
            return (value >= 0 && value <= 3) ? Privilege.values()[value + 1] : Privilege.FRAMEWORK_PRIVILEGE_UNKNOWN;
        } catch (RemoteException e) {
            throw new ServiceException(e);
        }
    }

    /**
     * Additional methods provided by specific Xposed framework
     *
     * @param name Featured method name
     * @param args Featured method arguments
     * @return Featured method result
     * @throws UnsupportedOperationException If the framework does not provide a method with given name
     * @throws ServiceException              If the service is dead or error occurred
     * @deprecated Normally, modules should never rely on implementation details about the Xposed framework,
     * but if really necessary, this method can be used to acquire such information
     */
    @Deprecated
    @Nullable
    public Bundle featuredMethod(@NonNull String name, @Nullable Bundle args) throws UnsupportedOperationException {
        try {
            return mService.featuredMethod(name, args);
        } catch (RemoteException e) {
            throw new ServiceException(e);
        }
    }

    /**
     * Get the application scope of current module
     *
     * @return Module scope
     * @throws ServiceException If the service is dead or error occurred
     */
    @NonNull
    public List<String> getScope() {
        try {
            return mService.getScope();
        } catch (RemoteException e) {
            throw new ServiceException(e);
        }
    }

    /**
     * Request to add a new app to the module scope
     *
     * @param packageName Package name of the app to be added
     * @param callback    Callback to be invoked when the request is completed or error occurred
     * @throws ServiceException If the service is dead or error occurred
     */
    public void requestScope(@NonNull String packageName, @NonNull IXposedScopeCallback callback) {
        try {
            mService.requestScope(packageName, callback);
        } catch (RemoteException e) {
            throw new ServiceException(e);
        }
    }

    /**
     * Remove an app from the module scope
     *
     * @param packageName Package name of the app to be added
     * @return null if successful, or non-null with error message
     * @throws ServiceException If the service is dead or error occurred
     */
    @Nullable
    public String removeScope(@NonNull String packageName) {
        try {
            return mService.removeScope(packageName);
        } catch (RemoteException e) {
            throw new ServiceException(e);
        }
    }

    /**
     * Get remote preferences from Xposed framework
     *
     * @param group Group name
     * @return The preferences, null if the framework is embedded
     * @throws ServiceException If the service is dead or error occurred
     */
    @Nullable
    public SharedPreferences getRemotePreferences(@NonNull String group) {
        return mRemotePrefs.computeIfAbsent(group, k -> {
            try {
                return RemotePreferences.newInstance(this, k);
            } catch (RemoteException e) {
                throw new ServiceException(e);
            }
        });
    }

    /**
     * Delete a group of remote preferences
     *
     * @param group Group name
     * @throws ServiceException If the service is dead or error occurred
     */
    public void deleteRemotePreferences(@NonNull String group) {
        deletionLock.writeLock().lock();
        try {
            mService.deleteRemotePreferences(group);
            mRemotePrefs.computeIfPresent(group, (k, v) -> {
                v.setDeleted();
                return null;
            });
        } catch (RemoteException e) {
            throw new ServiceException(e);
        } finally {
            deletionLock.writeLock().unlock();
        }
    }

    /**
     * Open an InputStream to read a file from the module's shared data directory
     *
     * @param name File name
     * @return The InputStream, null if the framework is embedded
     * @throws ServiceException If the service is dead or error occurred
     */
    @Nullable
    public FileInputStream openRemoteFileInput(@NonNull String name) {
        try {
            var file = mService.openRemoteFile(name, MODE_READ_ONLY);
            if (file == null) return null;
            return new FileInputStream(file.getFileDescriptor());
        } catch (RemoteException e) {
            throw new ServiceException(e);
        }
    }

    /**
     * Open an OutputStream to write a file to the module's shared data directory
     *
     * @param name File name
     * @param mode Operating mode
     * @return The OutputStream, null if the framework is embedded
     * @throws ServiceException If the service is dead or error occurred
     */
    @Nullable
    public FileOutputStream openRemoteFileOutput(@NonNull String name, int mode) {
        try {
            var file = mService.openRemoteFile(name, mode);
            if (file == null) return null;
            return new FileOutputStream(file.getFileDescriptor());
        } catch (RemoteException e) {
            throw new ServiceException(e);
        }
    }

    /**
     * Delete a file in the module's shared data directory
     *
     * @param name File name
     * @return true if successful, false if failed or the framework is embedded
     * @throws ServiceException If the service is dead or error occurred
     */
    public boolean deleteRemoteFile(@NonNull String name) {
        try {
            return mService.deleteRemoteFile(name);
        } catch (RemoteException e) {
            throw new ServiceException(e);
        }
    }

    /**
     * List all files in the module's shared data directory
     *
     * @return The file list, null if the framework is embedded
     * @throws ServiceException If the service is dead or error occurred
     */
    @Nullable
    public String[] listRemoteFiles() {
        try {
            return mService.listRemoteFiles();
        } catch (RemoteException e) {
            throw new ServiceException(e);
        }
    }
}