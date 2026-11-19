#import <Foundation/Foundation.h>
#import <MediaPlayer/MediaPlayer.h>
#include <dispatch/dispatch.h>

typedef void (*OpenTuneCommand)(int, double);

void opentune_install_media_commands(OpenTuneCommand callback) {
    dispatch_async(dispatch_get_main_queue(), ^{
        MPRemoteCommandCenter *center = [MPRemoteCommandCenter sharedCommandCenter];
        NSArray<MPRemoteCommand *> *commands = @[
            center.playCommand, center.pauseCommand, center.togglePlayPauseCommand,
            center.nextTrackCommand, center.previousTrackCommand, center.changePlaybackPositionCommand,
        ];
        [commands enumerateObjectsUsingBlock:^(MPRemoteCommand *command, NSUInteger index, BOOL *stop) {
            (void)stop;
            command.enabled = NO;
            [command addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *event) {
                double seconds = index == 5 ? ((MPChangePlaybackPositionCommandEvent *)event).positionTime : 0;
                callback((int)index, seconds);
                return MPRemoteCommandHandlerStatusSuccess;
            }];
        }];
    });
}

void opentune_set_now_playing(const char *title, const char *artist, const char *album,
                            double duration, double elapsed, double rate) {
    NSString *name = title ? [NSString stringWithUTF8String:title] : nil;
    NSString *by = artist ? [NSString stringWithUTF8String:artist] : nil;
    NSString *on = album ? [NSString stringWithUTF8String:album] : nil;
    dispatch_async(dispatch_get_main_queue(), ^{
        MPNowPlayingInfoCenter *info = [MPNowPlayingInfoCenter defaultCenter];
        MPRemoteCommandCenter *commands = [MPRemoteCommandCenter sharedCommandCenter];
        BOOL active = name.length > 0;
        commands.playCommand.enabled = active;
        commands.pauseCommand.enabled = active;
        commands.togglePlayPauseCommand.enabled = active;
        commands.nextTrackCommand.enabled = active;
        commands.previousTrackCommand.enabled = active;
        commands.changePlaybackPositionCommand.enabled = active && duration > 0;
        if (!active) {
            info.nowPlayingInfo = nil;
            info.playbackState = MPNowPlayingPlaybackStateStopped;
            return;
        }
        NSMutableDictionary *metadata = [@{
            MPMediaItemPropertyTitle: name,
            MPMediaItemPropertyArtist: by ?: @"",
            MPMediaItemPropertyAlbumTitle: on ?: @"",
            MPNowPlayingInfoPropertyElapsedPlaybackTime: @(elapsed),
            MPNowPlayingInfoPropertyPlaybackRate: @(rate),
        } mutableCopy];
        if (duration > 0) metadata[MPMediaItemPropertyPlaybackDuration] = @(duration);
        info.nowPlayingInfo = metadata;
        info.playbackState = rate > 0 ? MPNowPlayingPlaybackStatePlaying : MPNowPlayingPlaybackStatePaused;
    });
}
