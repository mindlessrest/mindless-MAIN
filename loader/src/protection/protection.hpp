#pragma once

namespace authclient { class AuthClient; }

namespace mindless::protection
{

bool init();
void shutdown();
void setAuthClient(authclient::AuthClient* client);
bool checkAll();
void startWatchdog();
bool isCompromised();
const char* lastReason();

}
