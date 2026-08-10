#ifndef LIMBUS_TRANSLATION_RUNTIME_H
#define LIMBUS_TRANSLATION_RUNTIME_H

void configure_limbus_translation_runtime(const char *active_index_pointer);
void activate_limbus_translation_lifecycle_hook();
void on_limbus_translation_library_loaded(const char *name, void *handle);
void probe_limbus_translation_runtime(const char *library_path);

#endif
