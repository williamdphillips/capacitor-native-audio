import AVFoundation
import Foundation
import MediaPlayer

public class AudioManager {
    private var audioSources: [AudioSource] = []
    private var currentIndex: Int = 0
    private let audioPlayer = AVPlayer()
    private var audioPlayerVolume: Float = 1.0
    static let eventEmitter = EventEmitter()
    private var airplayEnabled: Bool = false
    private var airPlayActive: Bool = false

    // Global Callbacks
    var onAudioEnd: (() -> Void)?
    var onPlaybackStatusChange: ((Bool) -> Void)?
    var onPlayNext: (() -> Void)?
    var onPlayPrevious: (() -> Void)?
    var onSeek: ((Double) -> Void)?
    var onAirPlayActiveChange: ((Bool) -> Void)?
    var onPlaybackError: ((String, String) -> Void)?  // audioId, error message

    init() {
        configureAudioSession()
        configureRemoteCommands()
        observeNotifications()
    }

    // MARK: - Audio Source Management

    func addAudioSource(_ source: AudioSource) throws {
        guard !audioSources.contains(where: { $0.audioId == source.audioId }) else {
            throw AudioPlayerError.runtimeError("Audio source with the same ID already exists")
        }
        print("Appended \(source.audioId)")
        audioSources.append(source)
    }

    func addAudioSources(_ sources: [AudioSource]) throws {
        for source in sources {
            try addAudioSource(source)
        }
    }

    func setAudioSources(_ sources: [AudioSource]) throws {
        // Stop current playback and clear existing sources
        audioSources.removeAll()

        // Add new sources
        try addAudioSources(sources)
        currentIndex = 0  // Reset to the first source

        print("Audio sources updated successfully")
    }

    func replaceAudioSource(withId audioId: String, newSource: AudioSource) throws {
        guard let index = audioSources.firstIndex(where: { $0.audioId == audioId }) else {
            throw AudioPlayerError.runtimeError("Audio source with ID \(audioId) not found")
        }
        audioSources[index] = newSource
        if currentIndex == index {
            try play(newSource)
        }
    }

    func getCurrentAudioSource() -> AudioSource? {
        guard currentIndex >= 0 && currentIndex < audioSources.count else { return nil }
        return audioSources[currentIndex]
    }

    func getAllAudioSources() -> [AudioSource] {
        return audioSources
    }

    func removeAudioSource(withId audioId: String) throws {
        guard let index = audioSources.firstIndex(where: { $0.audioId == audioId }) else {
            throw AudioPlayerError.runtimeError("Audio source with ID \(audioId) not found")
        }
        
        // Don't allow removing the currently playing song
        if currentIndex == index {
            throw AudioPlayerError.runtimeError("Cannot remove currently playing audio source")
        }
        
        // Adjust currentIndex if necessary
        if index < currentIndex {
            currentIndex -= 1
        }
        
        audioSources.remove(at: index)
        print("Removed audio source: \(audioId)")
    }

    func removeAudioSources(withIds audioIds: [String]) throws {
        for audioId in audioIds {
            // Skip if it's the currently playing song
            if let currentSource = getCurrentAudioSource(), currentSource.audioId == audioId {
                print("Skipping removal of currently playing audio source: \(audioId)")
                continue
            }
            
            if let index = audioSources.firstIndex(where: { $0.audioId == audioId }) {
                // Adjust currentIndex if necessary
                if index < currentIndex {
                    currentIndex -= 1
                }
                audioSources.remove(at: index)
                print("Removed audio source: \(audioId)")
            }
        }
    }

    // MARK: - Playback Controls

    private var playerItemStatusObserver: NSKeyValueObservation?

    func play(_ source: AudioSource? = nil) throws {
        // Check if the provided source is nil, and if so, attempt to resume the current item
        if source == nil {
            guard let currentItem = audioPlayer.currentItem else {
                throw AudioPlayerError.runtimeError("No current item to resume playback")
            }

            if audioPlayer.timeControlStatus == .playing {
                print("Audio is already playing.")
                return
            } else {
                print("Resuming playback")
                audioPlayer.play()
                updateNowPlayingInfo()
                onPlaybackStatusChange?(true)
                if let currentSource = getCurrentAudioSource() {
                    AudioManager.eventEmitter.emit(
                        event: "playbackStatusChange",
                        data: ["audioId": currentSource.audioId, "isPlaying": true])
                }
                return
            }
        }

        guard let source = source else {
            throw AudioPlayerError.runtimeError("No source provided and no current item available")
        }

        // Check if the currently playing item matches the specified source
        if let currentItem = audioPlayer.currentItem,
            let currentURL = (currentItem.asset as? AVURLAsset)?.url.absoluteString,
            currentURL == source.source
        {
            if audioPlayer.timeControlStatus == .playing {
                print("Audio is already playing.")
                return
            } else {
                print("Resuming playback for \(source.title)")
                audioPlayer.play()
                updateNowPlayingInfo()
                onPlaybackStatusChange?(true)
                AudioManager.eventEmitter.emit(
                    event: "playbackStatusChange",
                    data: ["audioId": source.audioId, "isPlaying": true])
                return
            }
        }

        // If the currently playing item doesn't match the specified source, find the matching source in the playlist
        if let matchingSource = audioSources.first(where: { $0.audioId == source.audioId }) {
            guard let url = URL(string: matchingSource.source) else {
                let errorMsg = "Invalid URL: \(matchingSource.source)"
                print("❌ \(errorMsg)")
                onPlaybackError?(matchingSource.audioId, errorMsg)
                AudioManager.eventEmitter.emit(
                    event: "playbackError",
                    data: ["audioId": matchingSource.audioId, "error": errorMsg])
                throw AudioPlayerError.invalidPath
            }

            // Validate file URLs exist
            if url.isFileURL {
                let filePath = url.path
                let fileManager = FileManager.default
                if !fileManager.fileExists(atPath: filePath) {
                    let errorMsg = "File not found: \(filePath)"
                    print("❌ \(errorMsg)")
                    onPlaybackError?(matchingSource.audioId, errorMsg)
                    AudioManager.eventEmitter.emit(
                        event: "playbackError",
                        data: ["audioId": matchingSource.audioId, "error": errorMsg])
                    throw AudioPlayerError.runtimeError(errorMsg)
                }
            }

            let playerItem = AVPlayerItem(url: url)
            audioPlayer.replaceCurrentItem(with: playerItem)

            // Observe the status of the player item
            playerItemStatusObserver = playerItem.observe(\.status, options: [.new, .initial]) {
                [weak self] item, _ in
                guard let self = self else { return }
                if item.status == .readyToPlay {
                    print("✅ Player item is ready to play")
                    self.updateNowPlayingInfo()
                } else if item.status == .failed {
                    let errorMsg = item.error?.localizedDescription ?? "Unknown error loading audio file"
                    print("❌ Failed to load player item: \(errorMsg)")
                    if let error = item.error {
                        print("Error details: \(error)")
                    }
                    
                    // Emit error event
                    self.onPlaybackError?(matchingSource.audioId, errorMsg)
                    AudioManager.eventEmitter.emit(
                        event: "playbackError",
                        data: ["audioId": matchingSource.audioId, "error": errorMsg])
                    
                    // Stop playback and clear the failed item
                    self.audioPlayer.pause()
                    self.audioPlayer.replaceCurrentItem(with: nil)
                    self.onPlaybackStatusChange?(false)
                    AudioManager.eventEmitter.emit(
                        event: "playbackStatusChange",
                        data: ["audioId": matchingSource.audioId, "isPlaying": false])
                    
                    // Try to skip to next track if available
                    let nextIndex = self.currentIndex + 1
                    if nextIndex < self.audioSources.count {
                        print("🔄 Attempting to skip to next track")
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                            let nextSource = self.audioSources[nextIndex]
                            do {
                                try self.play(nextSource)
                            } catch {
                                print("❌ Failed to play next track: \(error)")
                            }
                        }
                    }
                }
            }

            if let index = audioSources.firstIndex(where: { $0.audioId == matchingSource.audioId })
            {
                currentIndex = index
            } else {
                throw AudioPlayerError.runtimeError("Audio source not found in the playlist")
            }

            audioPlayer.play()
            onPlaybackStatusChange?(true)
            AudioManager.eventEmitter.emit(
                event: "playbackStatusChange",
                data: ["audioId": matchingSource.audioId, "isPlaying": true])

            print("Now playing: \(matchingSource.title)")
        } else {
            throw AudioPlayerError.runtimeError("Matching audio source not found in the playlist")
        }
    }

    func resume() {
        audioPlayer.play()
        onPlaybackStatusChange?(true)
        if let currentSource = getCurrentAudioSource() {
            AudioManager.eventEmitter.emit(
                event: "playbackStatusChange",
                data: ["audioId": currentSource.audioId, "isPlaying": true])
        }
    }

    func pause() {
        audioPlayer.pause()
        onPlaybackStatusChange?(false)
        if let currentSource = getCurrentAudioSource() {
            AudioManager.eventEmitter.emit(
                event: "playbackStatusChange",
                data: ["audioId": currentSource.audioId, "isPlaying": false])
        }
    }

    func stop() {
        audioPlayer.pause()
        audioPlayer.replaceCurrentItem(with: nil)
        updateNowPlayingInfo()
        print("Playback stopped and player reset")
    }

    func playNext() throws {
        onPlayNext?()
        print("onPlayNext callback triggered")
    }

    func playPrevious() throws {
        onPlayPrevious?()
        print("onPlayPrevious callback triggered")
    }

    func seek(to seconds: Double) throws {
        guard let duration = audioPlayer.currentItem?.duration else {
            throw AudioPlayerError.runtimeError(
                "Unable to get the duration of the current audio source")
        }

        let targetTime = CMTime(seconds: seconds, preferredTimescale: CMTimeScale(NSEC_PER_SEC))
        if targetTime >= .zero && targetTime <= duration {
            audioPlayer.seek(to: targetTime) { completed in
                if completed {
                    print("Seeked to \(seconds) seconds")
                    self.updateNowPlayingInfo()

                    // Trigger the onSeek callback
                    self.onSeek?(seconds)
                    print("onSeek callback triggered with time: \(seconds)")
                }
            }
        } else {
            throw AudioPlayerError.runtimeError(
                "Seek time \(seconds) is out of range (0 - \(CMTimeGetSeconds(duration)))")
        }
    }

    func seekForward(by seconds: Double = 10) throws {
        guard let currentItem = audioPlayer.currentItem else {
            throw AudioPlayerError.runtimeError("No active audio source to seek forward")
        }

        let currentTime = audioPlayer.currentTime()
        let newTime = CMTimeGetSeconds(currentTime) + seconds
        let duration = CMTimeGetSeconds(currentItem.duration)

        if newTime <= duration {
            audioPlayer.seek(
                to: CMTime(seconds: newTime, preferredTimescale: CMTimeScale(NSEC_PER_SEC)))
            print("Seeked forward by \(seconds) seconds")
            updateNowPlayingInfo()

            // Trigger the onSeek callback
            self.onSeek?(newTime)
            print("onSeek callback triggered with time: \(newTime)")
        } else {
            print("Seek forward time exceeds duration")
        }
    }

    func seekBackward(by seconds: Double = 10) throws {
        let currentTime = audioPlayer.currentTime()
        let newTime = max(CMTimeGetSeconds(currentTime) - seconds, 0)

        audioPlayer.seek(
            to: CMTime(seconds: newTime, preferredTimescale: CMTimeScale(NSEC_PER_SEC)))
        print("Seeked backward by \(seconds) seconds")
        updateNowPlayingInfo()

        // Trigger the onSeek callback
        self.onSeek?(newTime)
        print("onSeek callback triggered with time: \(newTime)")
    }

    func setVolume(_ volume: Float) {
        guard volume >= 0.0 && volume <= 1.0 else {
            print("Volume must be between 0.0 and 1.0")
            return
        }
        audioPlayer.volume = volume
        audioPlayerVolume = volume
        print("Volume set to \(volume)")
    }

    func setRate(rate: Float) {
        audioPlayer.rate = rate
        print("Playback rate set to \(rate)")
    }

    func isPlaying() -> Bool {
        return audioPlayer.rate != 0 && audioPlayer.error == nil
    }

    // MARK: - Playback Information

    func getCurrentDuration() -> Double {
        guard let currentItem = audioPlayer.currentItem else {
            print("No active audio source.")
            return 0.0
        }

        if currentItem.status == .readyToPlay {
            let duration = currentItem.asset.duration
            return duration.isNumeric ? CMTimeGetSeconds(duration) : 0.0
        }

        // If already failed, return 0.0 immediately
        if currentItem.status == .failed {
            print("❌ Player item failed to load, cannot get duration")
            if let error = currentItem.error {
                print("Error details: \(error.localizedDescription)")
            }
            return 0.0
        }

        // Wait for the player item to become ready with timeout
        let semaphore = DispatchSemaphore(value: 0)
        var duration: Double = 0.0
        var observer: NSKeyValueObservation?

        observer = currentItem.observe(\.status, options: [.new]) { item, _ in
            if item.status == .readyToPlay {
                let itemDuration = item.asset.duration
                duration = itemDuration.isNumeric ? CMTimeGetSeconds(itemDuration) : 0.0
                semaphore.signal()
            } else if item.status == .failed {
                print("❌ Player item failed while waiting for duration")
                if let error = item.error {
                    print("Error details: \(error.localizedDescription)")
                }
                semaphore.signal()  // Signal to unblock, return 0.0
            }
        }

        // Wait with 10 second timeout
        let timeoutResult = semaphore.wait(timeout: .now() + 10.0)
        observer?.invalidate()  // Clean up the observer
        
        if timeoutResult == .timedOut {
            print("❌ Timeout waiting for player item to become ready (getCurrentDuration)")
            return 0.0
        }
        
        return duration
    }

    func getCurrentTime() -> Double {
        guard let currentItem = audioPlayer.currentItem else {
            print("No active audio source.")
            return 0.0
        }

        if currentItem.status == .readyToPlay {
            let currentTime = audioPlayer.currentTime()
            return currentTime.isNumeric ? CMTimeGetSeconds(currentTime) : 0.0
        }

        // If already failed, return 0.0 immediately
        if currentItem.status == .failed {
            print("❌ Player item failed to load, cannot get current time")
            if let error = currentItem.error {
                print("Error details: \(error.localizedDescription)")
            }
            return 0.0
        }

        // Wait for the player item to become ready with timeout
        let semaphore = DispatchSemaphore(value: 0)
        var time: Double = 0.0
        var observer: NSKeyValueObservation?

        observer = currentItem.observe(\.status, options: [.new]) { item, _ in
            if item.status == .readyToPlay {
                let itemTime = self.audioPlayer.currentTime()
                time = itemTime.isNumeric ? CMTimeGetSeconds(itemTime) : 0.0
                semaphore.signal()
            } else if item.status == .failed {
                print("❌ Player item failed while waiting for current time")
                if let error = item.error {
                    print("Error details: \(error.localizedDescription)")
                }
                semaphore.signal()  // Signal to unblock, return 0.0
            }
        }

        // Wait with 10 second timeout
        let timeoutResult = semaphore.wait(timeout: .now() + 10.0)
        observer?.invalidate()  // Clean up the observer
        
        if timeoutResult == .timedOut {
            print("❌ Timeout waiting for player item to become ready (getCurrentTime)")
            return 0.0
        }
        
        return time
    }

    // MARK: - Metadata Updates

    private func updateNowPlayingInfo() {
        let nowPlayingInfoCenter = MPNowPlayingInfoCenter.default()
        var nowPlayingInfo: [String: Any] = [:]

        guard let audioSource = self.getCurrentAudioSource() else {
            print("No audio source available. Skipping Now Playing Info update.")
            return
        }

        // Set metadata fields
        nowPlayingInfo[MPMediaItemPropertyTitle] = audioSource.title
        nowPlayingInfo[MPMediaItemPropertyArtist] = audioSource.artist
        
        // Only set albumTitle if it exists and is not empty
        if let albumTitle = audioSource.albumTitle, !albumTitle.isEmpty {
            nowPlayingInfo[MPMediaItemPropertyAlbumTitle] = albumTitle
        }

        // Set playback duration and elapsed time
        nowPlayingInfo[MPMediaItemPropertyPlaybackDuration] = self.getCurrentDuration()
        nowPlayingInfo[MPNowPlayingInfoPropertyElapsedPlaybackTime] = self.getCurrentTime()
        nowPlayingInfo[MPNowPlayingInfoPropertyPlaybackRate] = 1

        // Fetch and set artwork if available
        if let artworkSource = audioSource.artworkSource, !artworkSource.isEmpty {
            fetchArtwork(from: artworkSource) { [weak self] artwork in
                guard let self = self else { return }
                var finalNowPlayingInfo = nowPlayingInfo
                if let artwork = artwork {
                    finalNowPlayingInfo[MPMediaItemPropertyArtwork] = artwork
                }
                DispatchQueue.main.async {
                    nowPlayingInfoCenter.nowPlayingInfo = finalNowPlayingInfo
                    print("Now playing info updated: \(finalNowPlayingInfo)")
                }
            }
        } else {
            // No artwork, set now playing info immediately
            DispatchQueue.main.async {
                nowPlayingInfoCenter.nowPlayingInfo = nowPlayingInfo
                print("Now playing info updated: \(nowPlayingInfo)")
            }
        }
    }

    private func fetchArtwork(
        from source: String, completion: @escaping (MPMediaItemArtwork?) -> Void
    ) {
        if source.starts(with: "data:image/") {
            // Base64 Image Handling
            guard let dataString = source.components(separatedBy: ",").last,
                let imageData = Data(base64Encoded: dataString),
                let image = UIImage(data: imageData)
            else {
                print("Failed to decode base64 image data")
                completion(nil)
                return
            }

            let artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
            completion(artwork)
            print("Artwork loaded from base64 image data")
        } else if source.starts(with: "file://") {
            // Local File Path Handling
            let fileURL = URL(fileURLWithPath: source.replacingOccurrences(of: "file://", with: ""))
            do {
                let imageData = try Data(contentsOf: fileURL)
                if let image = UIImage(data: imageData) {
                    let artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
                    completion(artwork)
                    print("Artwork loaded from local file path")
                    return
                } else {
                    print("Failed to create image from local file")
                    completion(nil)
                }
            } catch {
                print("Error loading local file: \(error)")
                completion(nil)
            }
        } else if let url = URL(string: source) {
            // URL-based Image Handling
            URLSession.shared.dataTask(with: url) { data, _, error in
                if let error = error {
                    print("Error fetching artwork: \(error.localizedDescription)")
                    completion(nil)
                    return
                }

                if let data = data, let image = UIImage(data: data) {
                    let artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
                    completion(artwork)
                    print("Artwork loaded from URL")
                } else {
                    print("Failed to create artwork from data")
                    completion(nil)
                }
            }.resume()
        } else {
            print("Invalid artwork source")
            completion(nil)
        }
    }

    private func configureRemoteCommands() {
        let commandCenter = MPRemoteCommandCenter.shared()

        // Next Track Command
        commandCenter.nextTrackCommand.isEnabled = true
        commandCenter.nextTrackCommand.addTarget { [weak self] _ in
            guard let self = self else { return .commandFailed }
            do {
                try self.playNext()
                print("Next track command executed")
                return .success
            } catch {
                print("Failed to play next track: \(error)")
                return .commandFailed
            }
        }

        // Previous Track Command
        commandCenter.previousTrackCommand.isEnabled = true
        commandCenter.previousTrackCommand.addTarget { [weak self] _ in
            guard let self = self else { return .commandFailed }
            do {
                try self.playPrevious()
                print("Previous track command executed")
                return .success
            } catch {
                print("Failed to play previous track: \(error)")
                return .commandFailed
            }
        }

        // Disable Skip Forward Command
        commandCenter.skipForwardCommand.isEnabled = false

        // Disable Skip Backward Command
        commandCenter.skipBackwardCommand.isEnabled = false

        // Play Command
        commandCenter.playCommand.isEnabled = true
        commandCenter.playCommand.addTarget { [weak self] _ in
            guard let self = self, let currentSource = self.getCurrentAudioSource() else {
                print("No current audio source available to play")
                return .commandFailed
            }

            do {
                try self.play(currentSource)
                return .success
            } catch {
                print("Failed to play audio: \(error)")
                return .commandFailed
            }
        }

        // Pause Command
        commandCenter.pauseCommand.isEnabled = true
        commandCenter.pauseCommand.addTarget { [weak self] _ in
            self?.pause()
            return .success
        }

        // Slider seeking
        commandCenter.changePlaybackPositionCommand.isEnabled = true
        commandCenter.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let self = self, let event = event as? MPChangePlaybackPositionCommandEvent else {
                return .commandFailed
            }

            do {
                print("Seeking to position: \(event.positionTime)")
                try self.seek(to: event.positionTime)
                return .success
            } catch {
                print("Failed to seek: \(error)")
                return .commandFailed
            }
        }
    }

    private func updateCurrentMetadata() {
        guard let currentSource = self.getCurrentAudioSource() else {
            print("No current audio source available")
            return
        }
        updateNowPlayingInfo()
    }

    // MARK: - Background Audio

    func configureAudioSession() {
        do {
            let audioSession = AVAudioSession.sharedInstance()
            try audioSession.setCategory(
                .playback, mode: .default, options: [.allowBluetooth, .allowAirPlay])
            try audioSession.setActive(true, options: .notifyOthersOnDeactivation)
            print("Audio session configured for background playback")
        } catch {
            print("Failed to configure audio session: \(error)")
        }
    }

    // MARK: - AirPlay Support

    func isAirPlayActive() -> Bool {
        let audioSession = AVAudioSession.sharedInstance()
        return audioSession.currentRoute.outputs.contains { $0.portType == .airPlay }
    }

    func observeNotifications() {
        // Observe route changes to track AirPlay connection and disconnection
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleRouteChange),
            name: AVAudioSession.routeChangeNotification,
            object: nil
        )

        // // Observe seek events using AVPlayerItem.timeJumpedNotification
        // NotificationCenter.default.removeObserver(self, name: AVPlayerItem.timeJumpedNotification, object: nil)
        // NotificationCenter.default.addObserver(
        //     self,
        //     selector: #selector(handleSeekEvent),
        //     name: AVPlayerItem.timeJumpedNotification,
        //     object: nil
        // )

        // Observe interruptions (e.g., phone call or app interruption)
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleInterruption),
            name: AVAudioSession.interruptionNotification,
            object: nil
        )

        // Observe song end events
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handlePlaybackEnd),
            name: .AVPlayerItemDidPlayToEndTime,
            object: nil
        )
    }

    @objc private func handleInterruption(notification: Notification) {
        guard let userInfo = notification.userInfo,
            let interruptionType = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt
        else {
            return
        }

        switch interruptionType {
        case AVAudioSession.InterruptionType.began.rawValue:
            print("Audio interruption began")
            audioPlayer.pause()
        case AVAudioSession.InterruptionType.ended.rawValue:
            print("Audio interruption ended")
            let options = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt
            if options == AVAudioSession.InterruptionOptions.shouldResume.rawValue {
                audioPlayer.play()
            }
        default:
            break
        }
    }

    @objc private func handlePlaybackEnd(notification: Notification) {
        onAudioEnd?()
    }

    @objc private func handleSeekEvent(notification: Notification) {
        guard isAirPlayActive() else {
            print("Seek event not triggered by AirPlay")
            return
        }

        let currentTime = getCurrentTime()
        print("AirPlay Seek Event at: \(currentTime) seconds")
        onSeek?(currentTime)  // Trigger your seek hook
    }

    @objc private func handleRouteChange(notification: Notification) {
        print("Route change detected")

        guard let userInfo = notification.userInfo,
            let reasonValue = userInfo[AVAudioSessionRouteChangeReasonKey] as? UInt,
            let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue)
        else {
            print("No valid route change reason found")
            return
        }

        let audioSession = AVAudioSession.sharedInstance()

        switch reason {
        case .newDeviceAvailable:
            print("New device connected")

            if isAirPlayActive() {
                airPlayActive = true
                print("AirPlay is now active")
                onAirPlayActiveChange?(true)  // Notify listeners
            } else {
                print("Non-AirPlay device connected")
            }

        case .oldDeviceUnavailable:
            print("Device disconnected")

            if !isAirPlayActive() {
                airPlayActive = false
                print("AirPlay is no longer active")
                onAirPlayActiveChange?(false)  // Notify listeners
            } else {
                print("Disconnected from non-AirPlay device")
            }

        case .routeConfigurationChange:
            print("Route configuration changed")

        case .categoryChange:
            print("Audio session category changed")
            // Check AirPlay status when category changes
            let wasAirPlayActive = airPlayActive
            airPlayActive = isAirPlayActive()

            if airPlayActive != wasAirPlayActive {
                if airPlayActive {
                    print("AirPlay became active")
                    onAirPlayActiveChange?(true)  // Notify listeners
                } else {
                    print("AirPlay is no longer active")
                    onAirPlayActiveChange?(false)  // Notify listeners
                }
            }

        default:
            print("Unhandled route change reason: \(reason)")
        }
    }
}
