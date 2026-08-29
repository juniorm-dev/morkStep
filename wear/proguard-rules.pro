# morkStep Wear — R8 rules
#
# The app tile itself (activity, HR relay, message listener, VibrateRelay)
# is tiny and fully referenced; keep the whole com.morkstep.wear package so
# the binder/service callbacks survive R8 untouched. The win comes from
# stripping the library payload (Guava, health-services client internals),
# which R8 does automatically via the libraries' consumer rules.
-keep class com.morkstep.wear.** { *; }

# Guava uses reflection internally; the used subset is kept via its consumer
# rules. Silence warnings about classes only referenced reflectively.
-dontwarn com.google.common.**