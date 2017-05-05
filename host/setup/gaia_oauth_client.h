// Copyright 2013 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef REMOTING_HOST_SETUP_GAIA_OAUTH_CLIENT_H_
#define REMOTING_HOST_SETUP_GAIA_OAUTH_CLIENT_H_

#include <queue>

#include "base/macros.h"
#include "base/memory/ref_counted.h"
#include "google_apis/gaia/gaia_oauth_client.h"
#include "net/url_request/url_request_context_getter.h"

#include "remoting/host/setup/oauth_client.h"

namespace net {
class URLRequestContext;
}

namespace remoting {

// A wrapper around gaia::GaiaOAuthClient that provides a more
// convenient interface, with queueing of requests and a callback
// rather than a delegate.
class GaiaOAuthClient : public OAuthClient,
                        public gaia::GaiaOAuthClient::Delegate {
 public:
  GaiaOAuthClient(
      scoped_refptr<net::URLRequestContextGetter> url_request_context_getter);

  ~GaiaOAuthClient() override;

  // Redeems |auth_code| using |oauth_client_info| to obtain |refresh_token| and
  // |access_token|, then uses the userinfo endpoint to obtain |user_email|.
  // Calls CompletionCallback with |user_email| and |refresh_token| when done,
  // or with empty strings on error.
  // If a request is received while another one is  being processed, it is
  // enqueued and processed after the first one is finished.
  void GetCredentialsFromAuthCode(
      const gaia::OAuthClientInfo& oauth_client_info,
      const std::string& auth_code,
      bool need_user_email,
      CompletionCallback on_done) override;

  // gaia::GaiaOAuthClient::Delegate
  void OnGetTokensResponse(const std::string& refresh_token,
                           const std::string& access_token,
                           int expires_in_seconds) override;
  void OnRefreshTokenResponse(const std::string& access_token,
                              int expires_in_seconds) override;
  void OnGetUserEmailResponse(const std::string& user_email) override;

  void OnOAuthError() override;
  void OnNetworkError(int response_code) override;

 private:
  struct Request {
    Request(const gaia::OAuthClientInfo& oauth_client_info,
            const std::string& auth_code,
            bool need_user_email,
            CompletionCallback on_done);
    Request(const Request& other);
    virtual ~Request();
    gaia::OAuthClientInfo oauth_client_info;
    std::string auth_code;
    bool need_user_email;
    CompletionCallback on_done;
  };

  void SendResponse(const std::string& user_email,
                    const std::string& refresh_token);

  std::queue<Request> pending_requests_;
  gaia::GaiaOAuthClient gaia_oauth_client_;
  std::string refresh_token_;
  bool need_user_email_;
  CompletionCallback on_done_;

  DISALLOW_COPY_AND_ASSIGN(GaiaOAuthClient);
};

}  // namespace remoting

#endif  // REMOTING_HOST_SETUP_GAIA_OAUTH_CLIENT_H_
