#import <Foundation/Foundation.h>
#import <IOKit/ps/IOPowerSources.h>
#import <IOKit/ps/IOPSKeys.h>
#import <Network/Network.h>
#include <stdatomic.h>

static atomic_bool smart_network_available;
static nw_path_monitor_t smart_monitor;

int opentune_smart_network_available(void) {
    static dispatch_once_t start;
    dispatch_once(&start, ^{
        smart_monitor = nw_path_monitor_create();
        nw_path_monitor_set_queue(smart_monitor, dispatch_queue_create("org.opentune.smart-downloads", DISPATCH_QUEUE_SERIAL));
        nw_path_monitor_set_update_handler(smart_monitor, ^(nw_path_t path) {
            atomic_store(&smart_network_available, nw_path_get_status(path) == nw_path_status_satisfied &&
                !nw_path_is_expensive(path) && !nw_path_is_constrained(path) &&
                (nw_path_uses_interface_type(path, nw_interface_type_wifi) ||
                 nw_path_uses_interface_type(path, nw_interface_type_wired)));
        });
        nw_path_monitor_start(smart_monitor);
    });
    return atomic_load(&smart_network_available) ? 1 : 0;
}

int opentune_smart_battery_percent(void) {
    @autoreleasepool {
        if (@available(macOS 12.0, *)) {
            if (NSProcessInfo.processInfo.lowPowerModeEnabled) return -1;
        }
        CFTypeRef sources = IOPSCopyPowerSourcesInfo();
        if (!sources) return -1;
        CFStringRef power = IOPSGetProvidingPowerSourceType(sources);
        if (power && CFEqual(power, CFSTR(kIOPSACPowerValue))) {
            CFRelease(sources);
            return 100;
        }
        int percent = -1;
        CFArrayRef list = IOPSCopyPowerSourcesList(sources);
        if (list) {
            for (CFIndex i = 0; i < CFArrayGetCount(list); ++i) {
                CFDictionaryRef description = IOPSGetPowerSourceDescription(sources, CFArrayGetValueAtIndex(list, i));
                if (!description) continue;
                CFNumberRef current = CFDictionaryGetValue(description, CFSTR(kIOPSCurrentCapacityKey));
                CFNumberRef max = CFDictionaryGetValue(description, CFSTR(kIOPSMaxCapacityKey));
                double level, capacity;
                if (current && max && CFNumberGetValue(current, kCFNumberDoubleType, &level) &&
                    CFNumberGetValue(max, kCFNumberDoubleType, &capacity) && level >= 0 && level <= capacity && capacity > 0) {
                    int remaining = (int) (100 * level / capacity);
                    if (percent < 0 || remaining < percent) percent = remaining;
                }
            }
            CFRelease(list);
        }
        CFRelease(sources);
        return percent;
    }
}
