#import <Foundation/Foundation.h>
#import <UserNotifications/UserNotifications.h>
#include <dispatch/dispatch.h>

typedef void (*OpenTuneReleaseClick)(const char *);

@interface OpenTuneReleaseNotifications : NSObject <UNUserNotificationCenterDelegate>
@property(nonatomic) OpenTuneReleaseClick opening;
@end

@implementation OpenTuneReleaseNotifications
- (void)userNotificationCenter:(UNUserNotificationCenter *)center
        willPresentNotification:(UNNotification *)notification
          withCompletionHandler:(void (^)(UNNotificationPresentationOptions))completionHandler {
    (void)center;
    (void)notification;
    completionHandler(UNNotificationPresentationOptionBanner | UNNotificationPresentationOptionList);
}

- (void)userNotificationCenter:(UNUserNotificationCenter *)center
 didReceiveNotificationResponse:(UNNotificationResponse *)response
          withCompletionHandler:(void (^)(void))completionHandler {
    (void)center;
    NSString *release = response.notification.request.content.userInfo[@"release"];
    if ([response.actionIdentifier isEqualToString:UNNotificationDefaultActionIdentifier] &&
        [release isKindOfClass:NSString.class] && self.opening) {
        self.opening(release.UTF8String);
    }
    completionHandler();
}
@end

static OpenTuneReleaseNotifications *delegate;

static UNUserNotificationCenter *notification_center(void) {
    // A development JVM might not have an application bundle.
    @try {
        return [UNUserNotificationCenter currentNotificationCenter];
    } @catch (NSException *exception) {
        (void)exception;
        return nil;
    }
}

void opentune_install_release_notifications(OpenTuneReleaseClick opening) {
    dispatch_async(dispatch_get_main_queue(), ^{
        delegate = [OpenTuneReleaseNotifications new];
        delegate.opening = opening;
        [notification_center() setDelegate:delegate];
    });
}

void opentune_authorize_release_notifications(void) {
    dispatch_async(dispatch_get_main_queue(), ^{
        [notification_center() requestAuthorizationWithOptions:UNAuthorizationOptionAlert
            completionHandler:^(BOOL granted, NSError *error) { (void)granted; (void)error; }];
    });
}

void opentune_deliver_release_notification(const char *id, const char *title, const char *subtitle) {
    NSString *identifier = [NSString stringWithUTF8String:id];
    NSString *headline = [NSString stringWithUTF8String:title];
    NSString *body = [NSString stringWithUTF8String:subtitle];
    if (!identifier.length || !headline.length || !body.length) return;
    dispatch_async(dispatch_get_main_queue(), ^{
        UNMutableNotificationContent *content = [UNMutableNotificationContent new];
        content.title = headline;
        content.body = body;
        content.userInfo = @{@"release": identifier};
        [notification_center() addNotificationRequest:[UNNotificationRequest requestWithIdentifier:identifier content:content trigger:nil]
            withCompletionHandler:nil];
    });
}
