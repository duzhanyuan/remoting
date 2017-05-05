// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "remoting/host/fake_desktop_environment.h"

#include <utility>

#include "base/memory/ptr_util.h"
#include "remoting/host/audio_capturer.h"
#include "remoting/host/desktop_capturer_proxy.h"
#include "remoting/host/input_injector.h"
#include "remoting/proto/event.pb.h"
#include "remoting/protocol/fake_desktop_capturer.h"

namespace remoting {

FakeInputInjector::FakeInputInjector() {}
FakeInputInjector::~FakeInputInjector() {}

void FakeInputInjector::Start(
    std::unique_ptr<protocol::ClipboardStub> client_clipboard) {}

void FakeInputInjector::InjectKeyEvent(const protocol::KeyEvent& event) {
  if (key_events_)
    key_events_->push_back(event);
}

void FakeInputInjector::InjectTextEvent(const protocol::TextEvent& event) {
  if (text_events_)
    text_events_->push_back(event);
}

void FakeInputInjector::InjectMouseEvent(const protocol::MouseEvent& event) {
  if (mouse_events_)
    mouse_events_->push_back(event);
}

void FakeInputInjector::InjectTouchEvent(const protocol::TouchEvent& event) {
  if (touch_events_)
    touch_events_->push_back(event);
}

void FakeInputInjector::InjectClipboardEvent(
    const protocol::ClipboardEvent& event) {
  if (clipboard_events_)
    clipboard_events_->push_back(event);
}

FakeScreenControls::FakeScreenControls() {}
FakeScreenControls::~FakeScreenControls() {}

void FakeScreenControls::SetScreenResolution(
    const ScreenResolution& resolution) {
}

FakeDesktopEnvironment::FakeDesktopEnvironment(
    scoped_refptr<base::SingleThreadTaskRunner> capture_thread)
    : capture_thread_(capture_thread) {}
FakeDesktopEnvironment::~FakeDesktopEnvironment() {}

// DesktopEnvironment implementation.
std::unique_ptr<AudioCapturer> FakeDesktopEnvironment::CreateAudioCapturer() {
  return nullptr;
}

std::unique_ptr<InputInjector> FakeDesktopEnvironment::CreateInputInjector() {
  std::unique_ptr<FakeInputInjector> result(new FakeInputInjector());
  last_input_injector_ = result->AsWeakPtr();
  return std::move(result);
}

std::unique_ptr<ScreenControls> FakeDesktopEnvironment::CreateScreenControls() {
  return base::MakeUnique<FakeScreenControls>();
}

std::unique_ptr<webrtc::DesktopCapturer>
FakeDesktopEnvironment::CreateVideoCapturer() {
  std::unique_ptr<protocol::FakeDesktopCapturer> fake_capturer(
      new protocol::FakeDesktopCapturer());
  if (!frame_generator_.is_null())
    fake_capturer->set_frame_generator(frame_generator_);

  std::unique_ptr<DesktopCapturerProxy> result(
      new DesktopCapturerProxy(capture_thread_));
  result->set_capturer(std::move(fake_capturer));
  return result;
}

std::unique_ptr<webrtc::MouseCursorMonitor>
FakeDesktopEnvironment::CreateMouseCursorMonitor() {
  return base::MakeUnique<FakeMouseCursorMonitor>();
}

std::string FakeDesktopEnvironment::GetCapabilities() const {
  return std::string();
}

void FakeDesktopEnvironment::SetCapabilities(const std::string& capabilities) {}

uint32_t FakeDesktopEnvironment::GetDesktopSessionId() const {
  return UINT32_MAX;
}

FakeDesktopEnvironmentFactory::FakeDesktopEnvironmentFactory(
    scoped_refptr<base::SingleThreadTaskRunner> capture_thread)
    : capture_thread_(capture_thread) {}
FakeDesktopEnvironmentFactory::~FakeDesktopEnvironmentFactory() {}

// DesktopEnvironmentFactory implementation.
std::unique_ptr<DesktopEnvironment> FakeDesktopEnvironmentFactory::Create(
    base::WeakPtr<ClientSessionControl> client_session_control) {
  std::unique_ptr<FakeDesktopEnvironment> result(
      new FakeDesktopEnvironment(capture_thread_));
  result->set_frame_generator(frame_generator_);
  last_desktop_environment_ = result->AsWeakPtr();
  return std::move(result);
}

void FakeDesktopEnvironmentFactory::SetEnableCurtaining(bool enable) {}

bool FakeDesktopEnvironmentFactory::SupportsAudioCapture() const {
  return false;
}

}  // namespace remoting
